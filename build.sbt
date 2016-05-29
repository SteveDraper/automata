name := "ascent"

version := "1.0"

libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.2.2"
libraryDependencies += "org.scalaz" %% "scalaz-concurrent" % "7.2.2"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")

scalaVersion := "2.11.8"
   
lazy val root = (project in file(".")).aggregate(automata,server,api)

lazy val automata =
  project
    .dependsOn(api)

lazy val api =
  project

lazy val server =
  project
    .dependsOn(
      automata,
      api)
