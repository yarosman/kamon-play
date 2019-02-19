import sbt.Credentials
import sbt.Path

resolvers ++= Seq[Resolver](
  "x2sy snapshots".at("https://nexus.x2sy.com/repository/snapshots/"),
  "x2sy releases".at("https://nexus.x2sy.com/repository/releases/")
)

credentials += Credentials(Path.userHome / ".ivy2" / ".x2sy-credentials")

addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent"       % "0.1.4")
addSbtPlugin("com.x2sy"          %% "kamon-sbt-umbrella" % "0.0.16-SNAPSHOT")
