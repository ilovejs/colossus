package colossus
package protocols.http

import core.DataBuffer

import akka.util.ByteString
import org.scalatest.{WordSpec, MustMatchers}


//NOTICE - all expected headers names must lowercase, otherwise these tests will fail equality testing

class HttpResponseParserSpec extends WordSpec with MustMatchers {

  "HttpResponseParser" must {

    "parse a response with no body" in {

      val res = "HTTP/1.1 200 OK\r\nHost: api.foo.bar:444\r\nAccept: */*\r\nAuthorization: Basic XXX\r\nAccept-Encoding: gzip, deflate\r\n\r\n"
      val parser = new HttpResponseParser

      val expected = HttpResponse(HttpVersion.`1.1`,
        HttpCodes.OK,
        ByteString(""),
        List("host"->"api.foo.bar:444", "accept"->"*/*", "authorization"->"Basic XXX", "accept-encoding"->"gzip, deflate"))
      parser.parse(DataBuffer(ByteString(res))).toList must equal(List(expected))

    }

    "parse a response with a body" in {
      val content = "{some : json}"
      val size = content.getBytes.size
      val res = s"HTTP/1.1 200 OK\r\nHost: api.foo.bar:444\r\nAccept: */*\r\nAuthorization: Basic XXX\r\nAccept-Encoding: gzip, deflate\r\nContent-Length: $size\r\n\r\n{some : json}"
      val parser = new HttpResponseParser

      val expected = HttpResponse(HttpVersion.`1.1`,
        HttpCodes.OK,
        ByteString("{some : json}"),
        List("host"->"api.foo.bar:444", "accept"->"*/*", "authorization"->"Basic XXX", "accept-encoding"->"gzip, deflate", "content-length"->size.toString))
      parser.parse(DataBuffer(ByteString(res))).toList must equal(List(expected))
    }

    "decode a response that was encoded by colossus with no body" in {
      val sent = HttpResponse(HttpVersion.`1.1`,
        HttpCodes.OK,
        ByteString(""),
        List("host"->"api.foo.bar:444", "accept"->"*/*", "authorization"->"Basic XXX", "accept-encoding"->"gzip, deflate"))

      val expected = sent.copy(headers = ("content-length"->"0") :: sent.headers)

      val serverProtocol = new HttpServerCodec
      val clientProtocol = new HttpClientCodec

      val encodedResponse = serverProtocol.encode(sent)

      val decodedResponse = clientProtocol.decode(encodedResponse)
      decodedResponse.toList must equal(List(expected))
    }

    "decode a response that was encoded by colosuss with a body" in {
      val content = "{some : json}"
      val size = content.getBytes.size

      val sent = HttpResponse(HttpVersion.`1.1`,
        HttpCodes.OK,
        ByteString("{some : json}"),
        List("host"->"api.foo.bar:444", "accept"->"*/*", "authorization"->"Basic XXX", "accept-encoding"->"gzip, deflate"))

      val expected = sent.copy(headers = ("content-length"->size.toString) :: sent.headers )

      val serverProtocol = new HttpServerCodec
      val clientProtocol = new HttpClientCodec

      val encodedResponse = serverProtocol.encode(sent)

      val decodedResponse = clientProtocol.decode(encodedResponse)
      decodedResponse.toList must equal(List(expected))
    }

  }


}
