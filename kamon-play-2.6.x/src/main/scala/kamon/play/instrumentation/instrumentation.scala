/* =========================================================================================
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

package kamon.play

import kamon.Kamon
import kamon.context.{Context, TextMap}
import play.api.libs.ws.StandaloneWSRequest

package object instrumentation {

  def encodeContext(ctx: Context, request: StandaloneWSRequest): StandaloneWSRequest = {
    val textMap = Kamon.contextCodec().HttpHeaders.encode(ctx)
    request.addHttpHeaders(textMap.values.toSeq: _*)
  }

  def context(headers: Map[String, String]): Context = {
    val headersTextMap = readOnlyTextMapFromHeaders(headers)
    Kamon.contextCodec().HttpHeaders.decode(headersTextMap)
  }

  private def readOnlyTextMapFromHeaders(headers: Map[String, String]): TextMap = new TextMap {
    override def values: Iterator[(String, String)]    = headers.iterator
    override def get(key: String): Option[String]      = headers.get(key)
    override def put(key: String, value: String): Unit = {}
  }

  def isError(statusCode: Int): Boolean =
    statusCode >= 500 && statusCode < 600

  def isMggError(statusCode: Int): Boolean = {
    val statusClass = statusCode / 100

    statusClass == 4 || statusClass == 5
  }

  object StatusCodes {
    val NotFound = 404
  }
}
