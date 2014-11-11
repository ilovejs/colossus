package colossus
package core

import akka.actor._
import java.net.InetSocketAddress

import akka.agent.Agent

import scala.concurrent.duration._

import metrics._

import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel, SocketChannel}
import java.net.ServerSocket

import scala.collection.JavaConversions._


/** Contains values for configuring how a Server operates 
 *
 * These are all lower-level configuration settings that are for the most part
 * not concerned with a server's application behavior
 *
 * The low/high watermark percentages are used to help mitigate connection
 * overload.  When a server hits the high watermark percentage of live
 * connections, it will change the idle timeout from `maxIdleTime` to
 * `highWaterMaxIdleTime` in an attempt to more aggressively close idle
 * connections.  This will continue until the percentage drops below
 * `lowWatermartPerentage`.  This can be totally disabled by just setting both
 * watermarks to 1.
 *
 * @param port Port on which this Server will accept connections
 * @param maxConnections Max number of simultaneous live connections
 * @param lowWatermarkPercentage Percentage of live/max connections which represent a normal state
 * @param highWatermarkPercentage Percentage of live/max connections which represent a high water state.  
 * @param maxIdleTime Maximum idle time for connections when in non high water conditions
 * @param highWaterMaxIdleTime Max idle time for connections when in high water conditions.  
 * @param tcpBacklogSize Set the max number of simultaneous connections awaiting accepting, or None for NIO default
 *
 */
case class ServerSettings(
  port: Int,
  maxConnections: Int = 1000, 
  maxIdleTime: Duration = Duration.Inf, 
  lowWatermarkPercentage: Double = 0.75, 
  highWatermarkPercentage: Double = 0.85, 
  highWaterMaxIdleTime : FiniteDuration = 100.milliseconds,
  tcpBacklogSize: Option[Int] = None
) {
  def lowWatermark = lowWatermarkPercentage * maxConnections
  def highWatermark = highWatermarkPercentage * maxConnections
}

/** Configuration used to specify a Server's application-level behavior
 *
 *  As opposed to ServerSettings which contains just lower-level config,
 *  ServiceConfig contains higher-level settings.  Ideally, abstraction layers
 *  on top of the core layer should provide an API that allows users to create
 *  a specialized server, where the delegator factory is provided by the API
 *  and the name and settings by the user.
 *
 * @param name Name of the Server, all reported metrics are prefixed using this name
 * @param delegatorFactory Factory to generate Delegators for each Worker
 * @param settings lower-level server configuration settings
 */
case class ServerConfig(
  name: MetricAddress, //possibly move this into settings as well, needs more experimentation
  delegatorFactory: Delegator.Factory,
  settings : ServerSettings
)

/**
 * A ServerRef is the public interface of a Server.  Servers should ONLY be interfaced with through this class.  Both from
 * an application design and from Akka idioms and best practices, passing around an actual Actor is strongly discouraged.
 * @param config The ServerConfig used to create this Server
 * @param server The ActorRef of the Server
 * @param system The IOSystem to which this Server belongs
 * @param serverState The current state of the Server.
 */
case class ServerRef(config: ServerConfig, server: ActorRef, system: IOSystem, private val serverState : Agent[ServerState]) {
  def name = config.name

  def maxIdleTime = {
    if(serverState().connectionVolumeState == ConnectionVolumeState.HighWater) {
      config.settings.highWaterMaxIdleTime
    } else {
      config.settings.maxIdleTime
    }
  }

  /**
   * Post a message to a Server's Delegator
   * @param message
   * @param sender
   * @return
   */
  def delegatorBroadcast(message: Any)(implicit sender: ActorRef = ActorRef.noSender) {
    server.!(Server.DelegatorBroadcast(message))(sender)
  }
}

/**
 * Class representing the state of the server.  Currently the only reported status is the ConnectionVolumeState.
 * @param connectionVolumeState Represents if the a Server's connections are normal or in highwater
 */
case class ServerState(connectionVolumeState : ConnectionVolumeState)

/**
 * ConnectionVolumeState indicates whether or not if the Server is operating with a normal workload, which is represented
 * by the current ratio of used / available connections being beneath the TimeoutConfig.highWatermarkPercentage amount.
 * Once this ratio is breached, the state will be changed to the HighWater state.
 *
 * In this state, the server will more aggressively timeout and close idle connections.  Effectively what this means is that
 * when the Server polls its connections in the Highwater state, any connection that is seen as idle for longer than TimeoutConfig.highWaterMaxIdleTime
 * will be reaped.  It does this in an effort to free up space.
 *
 * The Server will return back to its normal state when the connection ratio recedes past the lowWater mark.
 */
sealed trait ConnectionVolumeState

object ConnectionVolumeState {
  case object Normal extends ConnectionVolumeState
  case object HighWater extends ConnectionVolumeState
}

private[colossus] class Server(io: IOSystem, config: ServerConfig, stateAgent : Agent[ServerState]) extends Actor with ActorMetrics with ActorLogging with Stash {
  import Server._
  import WorkerManager._
  import context.dispatcher
  import config._
  import ServerStatus._
  import ConnectionVolumeState._

  val metricSystem = io.metrics
  val selector: Selector = Selector.open()
  val ssc = ServerSocketChannel.open()
  ssc.configureBlocking(false)
  val ss: ServerSocket = ssc.socket()
  val address = new InetSocketAddress(settings.port)
  ssc.register( selector, SelectionKey.OP_ACCEPT )

  val me = ServerRef(config, self, io, stateAgent)

  //initialize metrics
  val ratePeriods = List(1.seconds, 60.seconds)
  val connections   = metrics getOrAdd Counter(name / "connections")
  val refused       = metrics getOrAdd Rate(name / "refused_connections", ratePeriods)
  val connects      = metrics getOrAdd Rate(name / "connects", ratePeriods)
  val closed        = metrics getOrAdd Rate(name / "closed", ratePeriods)
  val highwaters    = metrics getOrAdd Rate(name / "highwaters")

  private var openConnections = 0

  def start() = {
    //setup the server
    try {
      ss.bind(address, settings.tcpBacklogSize.getOrElse(0))
      log.info(s"name: Bound to port ${settings.port}")
      true
    } catch {
      case t: Throwable => {
        log.error(s"bind failed: ${t.getMessage}, retrying")
        false
      }
    }
  }

  def receive = waitForWorkers


  def waitForWorkers: Receive = handleMetrics orElse handleShutdown orElse {
    case GetStatus => sender ! Initializing
    case GetInfo => sender ! ServerInfo(0, Initializing)
    case WorkersReady(workers) => {
      log.debug("Got workers, initializing")
      context.become(binding(workers))
      self ! RetryBind.start
      unstashAll()
    }
    case d: DelegatorBroadcast => stash()
  }

  case class RetryBind(nextDelay: FiniteDuration)
  object RetryBind {
    def from(currentDelay: FiniteDuration) = RetryBind(math.min(currentDelay.toMillis, 2.seconds.toMillis).milliseconds)
    def start = RetryBind(100.milliseconds)
  }

  //NOTE: this should throw if it can't bind.  I've seen issues where the port is taken and app keeps looping.  It should fail fast.
  //Or this behavior should be pluggable: ie, failOnNonBind=true
  def binding(router: ActorRef): Receive = handleMetrics orElse handleShutdown orElse {
    case GetStatus => sender ! Binding
    case GetInfo => sender ! ServerInfo(0, Binding)
    case DelegatorBroadcast(message) => router ! akka.routing.Broadcast(Worker.DelegatorMessage(me, message))
    case  RetryBind(delay) => {
      if (start()) {
        context.become(accepting(router))
        self ! Select
      } else {
        context.system.scheduler.scheduleOnce(delay, self, RetryBind.from(delay))
      }
    }
  }

  def accepting(router: ActorRef): Receive = handleMetrics orElse handleShutdown orElse {
    case GetStatus => sender ! Bound
    case Select => {
      selectLoop(router)
      self ! Select
    }
    case ConnectionClosed(id, cause) => {
      openConnections -= 1
      connections.decrement()
      closed.hit(tags = Map("cause" -> cause.toString))
      updateServerState()
    }
    case DelegatorBroadcast(message) => router ! akka.routing.Broadcast(Worker.DelegatorMessage(me, message))
    case GetInfo => sender ! ServerInfo(openConnections, Bound)
  }

  def handleShutdown: Receive = {
    case Terminated(w) => self ! PoisonPill
  }

  def selectLoop(router: ActorRef) {
    selector.select(5)
    val selectedKeys = selector.selectedKeys()
    val it = selectedKeys.iterator()

    while (it.hasNext) {
      val key : SelectionKey = it.next
      if (!key.isValid) {
        log.error("KEY IS INVALID")
        it.remove()
      } else if (key.isAcceptable) {
        // Accept the new connection
        try {
          val ssc : ServerSocketChannel = key.channel.asInstanceOf[ServerSocketChannel] //oh, java
          val sc: SocketChannel = ssc.accept()
          connects.hit()
          if (openConnections < settings.maxConnections) {
            openConnections += 1
            connections.increment()
            sc.configureBlocking(false)
            sc.socket.setTcpNoDelay(true)
            router ! Worker.NewConnection(sc)
            updateServerState()
          } else {
            sc.close()
            refused.hit()
          }
        } catch {
          case c: java.nio.channels.NotYetBoundException => log.error("Attempted to accept before bound!?")
          case error: Throwable => log.error(s"Error accepting connection: ${error.getClass.getName} - ${error.getMessage}")
        }
        it.remove()
      }
    }

  }

  override def preStart() {
    super.preStart()
    context.watch(io.workerManager)
    io.workerManager ! WorkerManager.RegisterServer(me, delegatorFactory)
    log.info("spinning up server")
  }

  override def postStop() {
    //cleanup
    selector.keys.foreach{_.channel.close}
    selector.close()
    ss.close()
    log.info("SERVER PEACE OUT")
  }

  private def updateServerState() {
    val connectionState = determineConnectionStateChange(openConnections, settings.lowWatermark, settings.highWatermark, stateAgent().connectionVolumeState)
    
    connectionState.foreach {
      x => stateAgent.send(ServerState(x))
      highwaters.hit()
    }
  }

  private def determineConnectionStateChange(currentCount : Int, lowCount : Double, highCount : Double, previousState : ConnectionVolumeState) : Option[ConnectionVolumeState] = {

    if(currentCount <= lowCount && previousState != Normal){
      Some(Normal)
    }else if(currentCount >= highCount && previousState != HighWater){
      Some(HighWater)
    }else { //if between water marks, or the states are identical
      None
    }
  }
}

//NOTE: rename, confusing with ServerState
/**
 * Represents the startup status of the server.  Can be either Initializing, Binding and Bound.
 */
sealed trait ServerStatus
object ServerStatus {
  case object Initializing extends ServerStatus
  case object Binding extends ServerStatus
  case object Bound extends ServerStatus
}

/**
 * Servers can be thought of as applications, as they provide Delegators and ConnectionHandlers which contain
 * application logic.  Servers are the objects that are directly interface with the Workers and provide them with the
 * Delegators and Handlers.  A Server will be "registered" with the Workers, and after a successful registration, it will
 * then bind to the specified ports and be ready to accept incoming requests.
 *
 * Also this includes all of the messages that Server will respond to.  Some of these can cause actions, others
 * are for when some internal event happens and the Server is notified.
 *
 */
object Server {
  case object Select
  case class ConnectionClosed(id: Long, cause : RootDisconnectCause)

  sealed trait ServerCommand
  case class Shutdown(killConnections: Boolean) extends ServerCommand
  case class DelegatorBroadcast(message: Any) extends ServerCommand
  case object GetStatus extends ServerCommand
  case object GetInfo extends ServerCommand

  case class ServerInfo(openConnections: Int, status: ServerStatus)


  /**
   * Create a server with the ServerConfig
   * @param config Contains the desired configuration of this Server
   * @param io The IOSystem to which this Server will belong
   * @return ServerRef which encapsulates the created Server
   */
  def apply(config: ServerConfig)(implicit io: IOSystem): ServerRef = {
    import io.actorSystem.dispatcher
    val serverStateAgent = Agent(ServerState(ConnectionVolumeState.Normal))
    val actor = io.actorSystem.actorOf(Props(classOf[Server], io, config, serverStateAgent).withDispatcher("server-dispatcher") ,name = config.name.idString)
    ServerRef(config, actor, io, serverStateAgent)
  }

  /**
   * Create a Server with this name and port and use a Colossus provided delegator to invoke the ConnectionHandler
   * factory function
   * @param name Name of this Server
   * @param port Port on which this Server will accept connections
   * @param acceptor The factory function to generate ConnectionHandlers.  This will run inside of a very simple Delegator.
   * @param io The IOSystem to which this Server will belong
   * @return ServerRef which encapsulates the created Server
   */
  def basic(name: String, port: Int, acceptor: () => ConnectionHandler)(implicit io: IOSystem): ServerRef = {
    val config = ServerConfig(
      name = name,
      delegatorFactory = Delegator.basic(acceptor),
      settings = ServerSettings(port = port) //all other settings are defaulted
    )
    apply(config)
  }
}
