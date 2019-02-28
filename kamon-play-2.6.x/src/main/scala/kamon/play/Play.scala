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

package kamon

package play

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

import _root_.play.api.libs.ws.StandaloneWSRequest
import _root_.play.api.libs.ws.StandaloneWSResponse
import _root_.play.api.mvc.RequestHeader
import com.typesafe.config.Config
import kamon.play.instrumentation.GenericRequest
import kamon.play.instrumentation.StatusCodes
import kamon.util.DynamicAccess

object Play {

  @volatile private var nameGenerator: NameGenerator =
    new DefaultNameGenerator()

  @volatile private var tagsGenerator: TagsGenerator =
    new DefaultTagsGenerator()

  loadConfiguration(Kamon.config())

  def generateOperationName(requestHeader: RequestHeader): String =
    nameGenerator.generateOperationName(requestHeader)

  def generateHttpClientOperationName(request: StandaloneWSRequest): String =
    nameGenerator.generateHttpClientOperationName(request)

  def tags = tagsGenerator

  private def loadConfiguration(config: Config): Unit = {
    val dynamic             = new DynamicAccess(getClass.getClassLoader)
    val nameGeneratorFQCN   = config.getString("kamon.play.name-generator")
    val wsTagsGeneratorFQCN = config.getString("kamon.play.tags-generator")
    nameGenerator = dynamic.createInstanceFor[NameGenerator](nameGeneratorFQCN, Nil).get

    tagsGenerator = dynamic.createInstanceFor[TagsGenerator](wsTagsGeneratorFQCN, Nil).get
  }

  Kamon.onReconfigure(new OnReconfigureHook {
    override def onReconfigure(newConfig: Config): Unit =
      Play.loadConfiguration(newConfig)
  })
}

trait NameGenerator {
  def generateOperationName(requestHeader: RequestHeader): String
  def generateHttpClientOperationName(request: StandaloneWSRequest): String
}

class DefaultNameGenerator extends NameGenerator {
  import java.util.Locale
  import _root_.play.api.routing.Router
  import _root_.scala.collection.concurrent.TrieMap

  private val cache            = TrieMap.empty[String, String]
  private val normalizePattern = """\$([^<]+)<[^>]+>""".r

  def generateOperationName(requestHeader: RequestHeader): String =
    requestHeader.attrs
      .get(Router.Attrs.HandlerDef)
      .map { handlerDef ⇒
        cache.getOrElseUpdate(
          s"${handlerDef.verb}${handlerDef.path}", {
            val traceName = {
              // Convert paths of form GET /foo/bar/$paramname<regexp>/blah to foo.bar.paramname.blah.get
              val p = normalizePattern
                .replaceAllIn(handlerDef.path, "$1")
                .replace('/', '.')
                .dropWhile(_ == '.')
              val normalisedPath = {
                if (p.lastOption.exists(_ != '.')) s"$p."
                else p
              }
              s"$normalisedPath${handlerDef.verb.toLowerCase(Locale.ENGLISH)}"
            }
            traceName
          }
        )
      }
      .getOrElse("UntaggedTrace")

  def generateHttpClientOperationName(request: StandaloneWSRequest): String =
    request.uri.getAuthority
}

trait TagsGenerator {

  def requestTags(request: GenericRequest): Map[String, String]

  def requestExceptionTags(ex: Throwable): Map[String, String]

  def wsTags(
      standaloneWSRequest: StandaloneWSRequest,
      standaloneWSResponse: StandaloneWSResponse
  ): Map[String, String]

  def wsExceptionTags(ex: Throwable): Map[String, String]

}

class DefaultTagsGenerator extends TagsGenerator {

  def requestTags(request: GenericRequest): Map[String, String] = Map.empty

  def requestExceptionTags(ex: Throwable): Map[String, String] = Map.empty

  def wsTags(
      standaloneWSRequest: StandaloneWSRequest,
      standaloneWSResponse: StandaloneWSResponse
  ): Map[String, String] = Map.empty

  def wsExceptionTags(ex: Throwable): Map[String, String] = ex match {
    case t: TimeoutException =>
      Map("http.status_code" -> StatusCodes.GatewayTimeout.toString, "error.class" -> t.getClass.getName)
    case c: ConnectException =>
      Map("http.status_code" -> StatusCodes.GatewayTimeout.toString, "error.class" -> c.getClass.getName)
    case s: SocketTimeoutException =>
      Map("http.status_code" -> StatusCodes.GatewayTimeout.toString, "error.class" -> s.getClass.getName)
    case u =>
      Map("http.status_code" -> StatusCodes.ServiceUnavailable.toString, "error.class" -> u.getClass.getName)
  }

}
