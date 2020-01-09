package kamon.instrumentation.play

import kamon.ClassLoading
import kamon.Kamon
import kanela.agent.libs.net.bytebuddy.asm.Advice
import play.api.mvc.RequestHeader
import play.api.routing.HandlerDef
import play.api.routing.Router

import scala.collection.concurrent.TrieMap

object GenerateOperationNameOnFilterHandler {

  @volatile private var _nameGenerator: NameGenerator = rebuildNameGenerator()

  Kamon.onReconfigure(_ => _nameGenerator = rebuildNameGenerator())

  private def rebuildNameGenerator(): NameGenerator = {
    val nameGeneratorClazz = Kamon.config().getString("kamon.instrumentation.play.http.server.operations.name-generator")
    _nameGenerator = ClassLoading.createInstance[NameGenerator](nameGeneratorClazz)
    _nameGenerator
  }

  @Advice.OnMethodEnter
  def enter(@Advice.Argument(0) request: RequestHeader): Unit = {
    request.attrs.get(Router.Attrs.HandlerDef).map { handler =>
      val span = Kamon.currentSpan()
      span.name(_nameGenerator.operationName(handler))
      span.takeSamplingDecision()
    }
  }

}

trait NameGenerator {

  def operationName(handlerDef: HandlerDef): String

}

object DefaultNameGenerator extends NameGenerator {

  private val _operationNameCache = TrieMap.empty[String, String]
  private val _normalizePattern = """\$([^<]+)<[^>]+>""".r

  def operationName(handlerDef: HandlerDef): String =
    _operationNameCache.getOrElseUpdate(handlerDef.path, {
      // Convert paths of form /foo/bar/$paramname<regexp>/blah to /foo/bar/paramname/blah
      _normalizePattern.replaceAllIn(handlerDef.path, "{$1}")
    })

}