package kamon.instrumentation.play

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.TimeoutException

import kamon.ClassLoading
import kamon.Kamon
import kamon.instrumentation.http.HttpClientInstrumentation
import kamon.instrumentation.http.HttpMessage
import kamon.tag.TagSet
import kamon.util.CallingThreadExecutionContext
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.RuntimeType
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.SuperCall
import play.api.Logger
import play.api.libs.ws.StandaloneWSRequest
import play.api.libs.ws.StandaloneWSResponse
import play.api.libs.ws.WSRequestExecutor
import play.api.libs.ws.WSRequestFilter

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class PlayClientInstrumentation extends InstrumentationBuilder {

  onSubTypesOf("play.api.libs.ws.StandaloneWSClient")
    .intercept(method("url"), classOf[WSClientUrlInterceptor])
}

class WSClientUrlInterceptor

object WSClientUrlInterceptor {

  private val log = Logger("WSClientUrlInterceptor")

  @RuntimeType
  def url(@SuperCall zuper: Callable[StandaloneWSRequest]): StandaloneWSRequest = {
    zuper
      .call()
      .withRequestFilter(_clientInstrumentationFilter)
  }

  @volatile private var _wsTagsGenerator: WsTagsGenerator                     = rebuildWsTagsGenerator()
  @volatile private var _httpClientInstrumentation: HttpClientInstrumentation = rebuildHttpClientInstrumentation()

  Kamon.onReconfigure { _ =>
    _httpClientInstrumentation = rebuildHttpClientInstrumentation()
    _wsTagsGenerator = rebuildWsTagsGenerator()
  }

  private def rebuildHttpClientInstrumentation(): HttpClientInstrumentation = {
    val httpClientConfig = Kamon.config().getConfig("kamon.instrumentation.play.http.client")
    _httpClientInstrumentation = HttpClientInstrumentation.from(httpClientConfig, "play.http.client")
    _httpClientInstrumentation
  }

  private def rebuildWsTagsGenerator(): WsTagsGenerator = {
    val wsTagsGeneratorClazz =
      Kamon.config().getString("kamon.instrumentation.play.http.client.tags-generator")
    Try(ClassLoading.createInstance[WsTagsGenerator](wsTagsGeneratorClazz)) match {
      case Failure(exception) =>
        log.error(s"Exception occurred on $wsTagsGeneratorClazz instance creation, used default", exception)
        new DefaultWsTagsGenerator
      case Success(value) => value
    }
  }

  private val _clientInstrumentationFilter = WSRequestFilter { rf: WSRequestExecutor =>
    new WSRequestExecutor {
      override def apply(request: StandaloneWSRequest): Future[StandaloneWSResponse] = {
        val currentContext = Kamon.currentContext()
        val requestHandler = _httpClientInstrumentation.createHandler(toRequestBuilder(request), currentContext)
        val responseFuture = Kamon.runWithSpan(requestHandler.span, finishSpan = false) {
          rf(requestHandler.request)
        }

        responseFuture.transform(
          s = response => {
            val tags = _wsTagsGenerator.requestTags(request, response)
            if (tags.nonEmpty()) {
              requestHandler.span
                .tagMetrics(_wsTagsGenerator.requestTags(request, response))
                .tag("http.uri", request.uri.toString)
            } else {
              requestHandler.span.tag("http.uri", request.uri.toString)
            }
            requestHandler.processResponse(toResponse(response))
            response
          },
          f = error => {
            val tags = _wsTagsGenerator.exceptionTags(error)

            requestHandler.span.tagMetrics(tags).fail(error).finish()
            error
          }
        )(CallingThreadExecutionContext)
      }
    }
  }

  private def toRequestBuilder(request: StandaloneWSRequest): HttpMessage.RequestBuilder[StandaloneWSRequest] =
    new HttpMessage.RequestBuilder[StandaloneWSRequest] {
      private var _newHttpHeaders: List[(String, String)] = List.empty

      override def write(header: String, value: String): Unit =
        _newHttpHeaders = (header -> value) :: _newHttpHeaders

      override def build(): StandaloneWSRequest =
        request.addHttpHeaders(_newHttpHeaders: _*)

      override def read(header: String): Option[String] =
        request.header(header)

      override def readAll(): Map[String, String] =
        request.headers.mapValues(_.head).toMap

      override def url: String =
        request.url

      override def path: String =
        request.uri.getPath

      override def method: String =
        request.method

      override def host: String =
        request.uri.getHost

      override def port: Int =
        request.uri.getPort
    }

  private def toResponse(response: StandaloneWSResponse): HttpMessage.Response = new HttpMessage.Response {
    override def statusCode: Int = response.status
  }
}

trait WsTagsGenerator {

  val NotFound           = "404"
  val ServiceUnavailable = "503"
  val GatewayTimeout     = "504"

  def requestTags(
      standaloneWSRequest: StandaloneWSRequest,
      standaloneWSResponse: StandaloneWSResponse
  ): TagSet

  def exceptionTags(ex: Throwable): TagSet

}

class DefaultWsTagsGenerator extends WsTagsGenerator {

  def requestTags(standaloneWSRequest: StandaloneWSRequest, standaloneWSResponse: StandaloneWSResponse): TagSet =
    TagSet.from(Map.empty[String, String])

  def exceptionTags(ex: Throwable): TagSet = ex match {
    case t: TimeoutException =>
      TagSet.from(Map("http.status_code" -> GatewayTimeout, "error.class" -> t.getClass.getName))
    case c: ConnectException =>
      TagSet.from(Map("http.status_code" -> GatewayTimeout, "error.class" -> c.getClass.getName))
    case s: SocketTimeoutException =>
      TagSet.from(Map("http.status_code" -> GatewayTimeout, "error.class" -> s.getClass.getName))
    case u =>
      TagSet.from(Map("http.status_code" -> ServiceUnavailable, "error.class" -> u.getClass.getName))
  }
}
