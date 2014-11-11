package org.http4s
package servlet


import java.io.{OutputStream, InputStream}
import java.util.concurrent.atomic.AtomicReference

import scodec.bits.ByteVector
import server._

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import java.net.InetAddress

import scala.collection.JavaConverters._
import javax.servlet._

import scala.concurrent.duration.Duration
import scalaz.concurrent.{Strategy, Actor, Task}
import scalaz.stream.Process
import scalaz.stream.Cause.{End, Terminated}
import scalaz.stream.io._
import scalaz.{\/, -\/, \/-}
import scala.util.control.NonFatal
import org.parboiled2.ParseError
import org.log4s.getLogger

class Http4sServlet(service: HttpService, 
                    asyncTimeout: Duration = Duration.Inf, 
                    chunkSize: Int = 4096)
            extends HttpServlet
{
  import Http4sServlet._

  private[this] val logger = getLogger

  private val asyncTimeoutMillis = if (asyncTimeout.isFinite) asyncTimeout.toMillis else -1  // -1 == Inf

  private[this] var serverSoftware: ServerSoftware = _
  private[this] var handler: AsyncContext => Task[Unit] = _

  override def init(config: ServletConfig) {
    val servletContext = config.getServletContext
    serverSoftware = ServerSoftware(servletContext.getServerInfo)
    val servletApiVersion = ServletApiVersion(servletContext)
    logger.info(s"Detected Servlet API version ${servletApiVersion}")

    handler = if (servletApiVersion >= ServletApiVersion(3, 1))
      servlet_3_1_handler
    else
      servlet_3_0_handler
  }

  override def service(servletRequest: HttpServletRequest, servletResponse: HttpServletResponse): Unit = {
    val ctx = servletRequest.startAsync()
    handler(ctx).runAsync {
      case \/-(_) =>
        ctx.complete()
      case -\/(t) =>
        logger.error(t)("error servicing request")
        if (!servletResponse.isCommitted)
          servletResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        ctx.complete()
    }
  }

  private def servlet_3_1_handler(ctx: AsyncContext): Task[Unit] = {
    val servletRequest = ctx.getRequest.asInstanceOf[HttpServletRequest]
    val servletResponse = ctx.getResponse.asInstanceOf[HttpServletResponse]

    parseRequest(servletRequest, asyncBodyReader) match {
      case \/-(request) =>
        type Callback = Throwable \/ Unit => Unit

        case object Init
        case object WritePossible
        case class WriteRequest(chunk: ByteVector, cb: Callback)
        case object Chunked

        val out = servletResponse.getOutputStream
        var state: Any = Init
        var flush: Boolean = false

        val actor = Actor[Any] {
          case WritePossible =>
            state match {
              case WriteRequest(chunk, cb) => write(out, chunk, flush, cb)
              case _ =>
            }
            state = WritePossible

          case t: Throwable =>
            state match {
              case WriteRequest(chunk, cb) => cb(-\/(t))
              case _ =>
            }
            state = t

          case r @ WriteRequest(chunk, cb) =>
            state match {
              case WritePossible if out.isReady => write(out, chunk, flush, cb)
              case t: Throwable => cb(-\/(t))
              case _ => state = r
            }

          case Chunked =>
            flush = true
        }(Strategy.Sequential)

        out.setWriteListener(new WriteListener {
          override def onWritePossible(): Unit = actor ! WritePossible
          override def onError(t: Throwable): Unit = actor ! t
        })

        Task.fork(service.or(request, ResponseBuilder.notFound(request))).flatMap { response =>
          renderServletResponseHead(servletResponse, response)
          if (response.isChunked)
            actor ! Chunked
          response.body.evalMap { chunk => Task.async[Unit] { cb => actor ! WriteRequest(chunk, cb)}}.run
        }
      case -\/(e) =>
        Task.now(onBadRequest(servletResponse, e))
    }
  }

  private def servlet_3_0_handler(ctx: AsyncContext): Task[Unit] = {
    val servletRequest = ctx.getRequest.asInstanceOf[HttpServletRequest]
    val servletResponse = ctx.getResponse.asInstanceOf[HttpServletResponse]
    parseRequest(servletRequest, syncBodyReader) match {
      case \/-(request) =>
        Task.fork(service.or(request, ResponseBuilder.notFound(request))).flatMap { response =>
          renderServletResponseHead(servletResponse, response)
          syncBodyWriter(servletResponse, response)
        }
      case -\/(e) =>
        Task.now(onBadRequest(servletResponse, e))
    }
  }

  private def parseRequest(req: HttpServletRequest, bodyReader: HttpServletRequest => EntityBody): ParseResult[Request] =
    for {
      method <- Method.fromString(req.getMethod)
      uri <- Uri.fromString(req.getRequestURI)
      version <- HttpVersion.fromString(req.getProtocol)
    } yield Request(
      method = method,
      uri = uri,
      httpVersion = version,
      headers = parseHeaders(req),
      body = bodyReader(req),
      attributes = AttributeMap(
        Request.Keys.PathInfoCaret(req.getServletPath.length),
        Request.Keys.Remote(InetAddress.getByName(req.getRemoteAddr)),
        Request.Keys.ServerSoftware(serverSoftware)
      )
    )

  private def parseHeaders(req: HttpServletRequest): Headers = {
    val headers = for {
      name <- req.getHeaderNames.asScala
      value <- req.getHeaders(name).asScala
    } yield Header(name, value)
    Headers(headers.toSeq : _*)
  }

  private def onBadRequest(servletResponse: HttpServletResponse, parseFailure: ParseFailure): Unit =
    servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, parseFailure.sanitized)

  private def renderServletResponseHead(servletResponse: HttpServletResponse, response: Response): Unit = {
    servletResponse.setStatus(response.status.code, response.status.reason)
    for (header <- response.headers)
      if (header.isNot(Header.`Transfer-Encoding`)) // TODO: Tomcat is rendering this twice and then not closing
        servletResponse.addHeader(header.name.toString, header.value)
  }

  private def syncBodyReader(servletRequest: HttpServletRequest): EntityBody =
    chunkR(servletRequest.getInputStream).map(_(chunkSize)).eval

  private def syncBodyWriter(servletResponse: HttpServletResponse, response: Response): Task[Unit] = {
    val out = servletResponse.getOutputStream
    val flush = response.isChunked
    response.body.map { chunk =>
      out.write(chunk.toArray)
      if (flush) out.flush()
    }.run
  }

  private def asyncBodyReader(servletRequest: HttpServletRequest): EntityBody = {
    type Callback = Throwable \/ ByteVector => Unit
    case object DataAvailable
    case object AllDataRead

    val in = servletRequest.getInputStream

    if (in.isFinished)
      Process.halt
    else {
      var callbacks: List[Callback] = Nil
      val actor = Actor[Any] {
        case cb: Callback =>
          if (in.isFinished)
            cb(-\/(Terminated(End)))
          else if (in.isReady)
            read(in, cb)
          else {
            // Consuming this stream on multiple threads can lead to multiple
            // callbacks accruing.  We shouldn't ever see this list grow beyond
            // one, but in the spirit of defensive programming, we'll try to
            // fulfill them all.
            callbacks = cb :: callbacks
          }

        case DataAvailable =>
          callbacks match {
            case winner :: losers =>
              read(in, winner)
              losers.foreach(_(\/-(ByteVector.empty)))
              callbacks = Nil
            case _ =>
          }

        case AllDataRead =>
          if (callbacks.nonEmpty) {
            callbacks.foreach(_(-\/(Terminated(End))))
            callbacks = Nil
          }

        case t: Throwable =>
          logger.error(t)("Error reading servlet input stream")
          if (callbacks.nonEmpty) {
            callbacks.foreach(_(-\/(t)))
            callbacks = Nil
          }
      }(Strategy.Sequential)

      in.setReadListener(new ReadListener {
        override def onError(t: Throwable): Unit = actor ! t
        override def onDataAvailable(): Unit = actor ! DataAvailable
        override def onAllDataRead(): Unit = actor ! AllDataRead
      })

      Process.repeatEval(Task.async[ByteVector] { actor ! _ })
    }
  }

  private def read(in: ServletInputStream, cb: Throwable \/ ByteVector => Unit): Unit =
    cb(\/.fromTryCatchNonFatal {
      val buff = new Array[Byte](chunkSize)
      val len = in.read(buff)
      if (len == chunkSize) {
        ByteVector.view(buff)
      }
      else if (len > 0) {
        // Need to truncate the array
        val b2 = new Array[Byte](len)
        System.arraycopy(buff, 0, b2, 0, len)
        ByteVector.view(b2)
      }
      else ByteVector.empty
    })

  private def write(out: ServletOutputStream, chunk: ByteVector, flush: Boolean, cb: Throwable \/ Unit => Unit): Unit =
    cb(\/.fromTryCatchNonFatal {
      out.write(chunk.toArray)
      if (flush && out.isReady)
        out.flush()
    })
}

