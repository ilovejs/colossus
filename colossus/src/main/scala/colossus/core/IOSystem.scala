package colossus

import core._

import akka.actor._
import metrics._
import task._

import java.net.InetSocketAddress

object IOSystem {

  def apply(config: IOSystemConfig, metrics: MetricSystem)(implicit system: ActorSystem): IOSystem = {
    val workerManager = system.actorOf(Props(classOf[WorkerManager], WorkerManagerConfig(config.numWorkers, metrics)), name = s"${config.name}-manager")
    val sys = IOSystem(workerManager, config, metrics, system)
    workerManager ! WorkerManager.Initialize(sys)
    sys
  }

  def apply(name: String, numWorkers: Int, metrics: MetricSystem)  
    (implicit system: ActorSystem): IOSystem = apply(IOSystemConfig(name, numWorkers), metrics)


  def apply(name: String, numWorkers: Int)(implicit system: ActorSystem): IOSystem = apply(name, numWorkers, MetricSystem.deadSystem)

  def apply(name: String = "iosystem", numWorkers: Option[Int] = None)(implicit sys: ActorSystem = ActorSystem(name)): IOSystem = {
    val workers = numWorkers.getOrElse (Runtime.getRuntime.availableProcessors())
    val metrics = MetricSystem(MetricAddress.Root / name)
    apply(name, workers, metrics)
  }

}

/**
 * Configuration used to specify an IOSystem's parameters.
 * @param name The name of the IOSystem
 * @param numWorkers The amount of Worker actors to spawn.  Must be greater than 0.
 */
case class IOSystemConfig(name: String, numWorkers: Int){
  require (numWorkers >= 0) //FIX - we currently need to allow 0 workers for tests
}

/*NOTE: We should look into converting this case class into a trait with a private impl */
/**
 * An IO System is basically a collection of worker actors.  You can attach
 * either servers or clients to an IOSystem.
 *
 * You may have multiple IOSystems per ActorSystem and actors attached to
 * different IOSystems have no rules about cross communication.  The main thing
 * to keep in mind is that all actors in a single IOSystem will share event
 * loops.
 */
case class IOSystem private[colossus](workerManager: ActorRef, config: IOSystemConfig, metrics: MetricSystem, actorSystem: ActorSystem) {
  import IOCommand._

  /**
   * Name of the IOSystem as specified in the IOSystemConfig
   * @return
   */
  def name = config.name

  /**
   * MetricAddress of this IOSystem as seen from the MetricSystem
   */
  val namespace = MetricAddress.Root / config.name

  /**
   * Sends a message to the underlying WorkerManager.
   * @param msg Msg to send
   * @param sender The sender of the message, defaults to ActorRef.noSender
   * @return
   */
  def ! (msg: Any)(implicit sender: ActorRef = ActorRef.noSender) = workerManager ! msg

  /**
   * Shuts down the IO System
   */
  def shutdown() {
    workerManager ! WorkerManager.Shutdown
  }
  /**
   * Shuts down the entire actor system
   */
  def apocalypse() {
    workerManager ! WorkerManager.Apocalypse
  }

  /**
   * Run the specified task on a Worker thread.
   * @param t The Task to run
   * @return The Task's ActorRef
   */
  def run(t: Task): ActorRef = {
    workerManager ! IOCommand.BindWorkerItem(t)
    t.proxy
  }

  /**
   * Connect to the specified address and use the specified function to create a Handler to attach to it.
   * @param address The address with which to connect.
   * @param handler Function which takes in the bound WorkerRef and creates a ClientConnectionHandler which is connected
   *                to the handler.
   */
  def connect(address: InetSocketAddress, handler: WorkerRef => ClientConnectionHandler) {
    workerManager ! Connect(address, handler)
  }
}

/**
 * An IO Command is sent to an IO System, which then routes the command to a
 * random worker using a round-robin router.  If you want more determinism over
 * how items are distributed across workers, just use multiple IO Systems
 */
sealed trait IOCommand

object IOCommand {

  /**
   * Connect to the specified address and use the specified function to create a Handler to attach to it.
   * @param address The address with which to connect.
   * @param handler Function which takes in the bound WorkerRef and creates a ClientConnectionHandler which is connected
   *                to the handler.
   */
  case class Connect(address: InetSocketAddress, handler: WorkerRef => ClientConnectionHandler) extends IOCommand


  /**
   * Bind the BindableWorkerItem to a Worker.  The only BindableWorkerItem currently supported is a Task.
   * @param item The item to bind.
   */
  case class BindWorkerItem(item: BindableWorkerItem) extends IOCommand

}
