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

val play26Version = "2.6.21"

val kamonCore      = "io.kamon"     %% "kamon-core"         % "1.1.5"
val kamonScala     = "io.kamon"     %% "kamon-scala-future" % "1.0.0"
val kamonTestkit   = "io.kamon"     %% "kamon-testkit"      % "1.1.1"
val typesafeConfig = "com.typesafe" % "config"              % "1.3.3"

//play 2.6.x
val play26          = "com.typesafe.play"      %% "play"                  % play26Version
val playNetty26     = "com.typesafe.play"      %% "play-netty-server"     % play26Version
val playAkkaHttp26  = "com.typesafe.play"      %% "play-akka-http-server" % play26Version
val playWS26        = "com.typesafe.play"      %% "play-ws"               % play26Version
val playLogBack26   = "com.typesafe.play"      %% "play-logback"          % play26Version
val playTest26      = "com.typesafe.play"      %% "play-test"             % play26Version
val scalatestplus26 = "org.scalatestplus.play" %% "scalatestplus-play"    % "3.1.2"

lazy val kamonPlay = Project("kamon-play", file("."))
//.settings(noPublishing: _*)
  .aggregate(kamonPlay26)

lazy val kamonPlay26 = Project("kamon-play-26", file("kamon-play-2.6.x"))
  .enablePlugins(JavaAgent)
  .settings(Seq(
    name := "kamon-play-2.6",
    scalaVersion := "2.12.8",
    //testGrouping in Test := singleTestPerJvm((definedTests in Test).value, (javaOptions in Test).value),
    organization := "com.x2sy",
    organizationName := "x2sy",
    organizationHomepage := Some(new URL("http://x2sy.com")),
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://nexus.x2sy.com/repository/"
      if (isSnapshot.value)
        Some("x2sy Snapshots".at(nexus + "snapshots/"))
      else
        Some("x2sy Releases".at(nexus + "releases/"))
    },
    credentials += Credentials(Path.userHome / ".ivy2" / ".x2sy-credentials")
  ))
  .settings(javaAgents += "org.aspectj" % "aspectjweaver" % "1.9.2" % "compile;test")
  .settings(
    libraryDependencies ++=
      compileScope(play26, playNetty26, playAkkaHttp26, playWS26, kamonCore, kamonScala) ++
        providedScope(aspectJ, typesafeConfig) ++
        testScope(playTest26, scalatestplus26, playLogBack26, kamonTestkit))

//def singleTestPerJvm(tests: Seq[TestDefinition], jvmSettings: Seq[String]): Seq[Group] =
//  tests map { test =>
//    Group(
//      name = test.name,
//      tests = Seq(test),
//      runPolicy = SubProcess(ForkOptions(runJVMOptions = jvmSettings)))
//  }
//
//enableProperCrossScalaVersionTasks
