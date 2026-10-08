addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")

// For TypeScript dependencies in client
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter" % "1.0.0-beta44")

// For development reloading of server
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

// For building a self-contained jar to deploy the server
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
