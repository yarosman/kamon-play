/*
 * =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play.instrumentation

import com.x2sy.interservice.{ApiResultError, ApiResultSuccess}
import kamon.Kamon
import kamon.play.Play
import kamon.trace.{Span, SpanCustomizer}
import kamon.util.CallingThreadExecutionContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{Around, Aspect, Pointcut}
import play.api.libs.json.{JsError, JsValue}
import play.api.libs.ws.{StandaloneWSRequest, StandaloneWSResponse, WSRequestExecutor, WSRequestFilter}
import play.api.libs.ws.JsonBodyReadables._

import scala.concurrent.Future
import scala.util.Try

@Aspect
class WSInstrumentation {
  import WSInstrumentation._wsInstrumentationFilter

  @Pointcut("execution(* play.api.libs.ws.StandaloneWSClient+.url(..))")
  def standaloneWsClientUrl() = {}

  @Around("standaloneWsClientUrl()")
  def aroundStandaloneWSClientUrl(pjp: ProceedingJoinPoint): Any =
    pjp
      .proceed()
      .asInstanceOf[StandaloneWSRequest]
      .withRequestFilter(_wsInstrumentationFilter)

}

object WSInstrumentation {

  val X_MGG_API_JSON_HEADER = "X-Mgg-Api-Json"

  private val _wsInstrumentationFilter = requestFilter()

  def requestFilter(): WSRequestFilter = WSRequestFilter { rf: WSRequestExecutor =>
    new WSRequestExecutor {
      override def apply(request: StandaloneWSRequest): Future[StandaloneWSResponse] = {
        val currentContext = Kamon.currentContext()
        val parentSpan     = currentContext.get(Span.ContextKey)

        val clientSpanBuilder = Kamon
          .buildSpan(Play.generateHttpClientOperationName(request))
          .asChildOf(parentSpan)
          .withMetricTag("span.kind", "client")
          .withMetricTag("component", "play.client.ws")
          .withMetricTag("http.method", request.method)
          .withTag("http.url", request.uri.toString)

        val clientRequestSpan = currentContext
          .get(SpanCustomizer.ContextKey)
          .customize(clientSpanBuilder)
          .start()

        val contextWithClientSpan = currentContext.withKey(Span.ContextKey, clientRequestSpan)
        val requestWithContext =
          encodeContext(
            contextWithClientSpan,
            request.withHttpHeaders((request.headers - X_MGG_API_JSON_HEADER).mapValues(_.head).toSeq: _*))
        val responseFuture = rf(requestWithContext)

        responseFuture.transform(
          s = response => {
            if (request.header(X_MGG_API_JSON_HEADER).isDefined) {
              Try(response.body[JsValue]).toOption.foreach {
                json =>
                  json.validate[ApiResultSuccess[JsValue]].asEither.swap.foreach {
                    _ =>
                      json
                        .validate[ApiResultError]
                        .fold(
                          _ => {
                            clientRequestSpan.tagMetric("error", "true")
                            clientRequestSpan.tagMetric("error.code", "500")
                            clientRequestSpan.tagMetric("error.is_expected", "false")
                          },
                          err => {
                            val isExpected = if (err.code / 100 == 5) false else true

                            clientRequestSpan.tagMetric("error", "true")
                            clientRequestSpan.tagMetric("error.code", err.code.toString)
                            clientRequestSpan.tagMetric("error.is_expected", isExpected.toString)
                          }
                        )
                  }
              }

              clientRequestSpan.tagMetric("http.status", response.status.toString)
            }

            clientRequestSpan.tagMetric("http.status_code", response.status.toString)

            if (isError(response.status))
              clientRequestSpan.addError("error")

            if (response.status == StatusCodes.NotFound)
              clientRequestSpan.setOperationName("not-found")

            clientRequestSpan.finish()
            response
          },
          f = error => {
            clientRequestSpan.addError("error.object", error)
            clientRequestSpan.finish()
            error
          }
        )(CallingThreadExecutionContext)
      }
    }
  }
}
