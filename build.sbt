name := "animalWellbeing"

val deployFast = taskKey[Unit]("Copies the fastLinkJS script to deployscripts/")
val deployFull = taskKey[Unit]("Copies the fullLinkJS script to deployscripts/")

import org.scalajs.linker.interface.ModuleSplitStyle

ThisBuild / scalaVersion := "3.5.2"

lazy val root = project.in(file("."))
  .aggregate(commonJS, commonJVM, awServer, awClient)
  .settings(
    Compile / fullLinkJSOutput / aggregate := false,

  )

lazy val common = crossProject(JVMPlatform, JSPlatform).in(file("common"))
  .settings(

    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "4.3.1",
      "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided"
    )

  )
lazy val commonJS = common.js
lazy val commonJVM = common.jvm

lazy val awClient = project.in(file("client"))
  .dependsOn(commonJS)
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(ScalablyTypedConverterExternalNpmPlugin)
  .settings(
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    libraryDependencies ++= Seq(
      "com.wbillingsley" %%% "doctacular" % "0.3.0",
    ),

    // For java.security.SecureRandom which is used in UUID generation
    libraryDependencies += ("org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13),

    // This is an application with a main method
    scalaJSUseMainModuleInitializer := true,
    
    // For vite bundler
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("animalWellbeing"))) 
    },

    // To use ScalablyTypedConverterExternalNpmPlugin
    externalNpm := {
      baseDirectory.value
    },

    // Used by GitHub Actions to get the script out from the .gitignored target directory
    deployFast := {
      val opt = (Compile / fastOptJS).value
      IO.copyFile(opt.data, new java.io.File("client/target/compiled.js"))
    },

    deployFull := {
      val opt = (Compile / fullOptJS).value
      IO.copyFile(opt.data, new java.io.File("client/target/compiled.js"))
    }
  )


lazy val awServer = project.in(file("server"))
  .dependsOn(commonJVM)
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "cask" % "0.10.2",
      "com.lihaoyi" %% "requests" % "0.9.0",

      "org.postgresql" % "postgresql" % "42.7.13",
      "com.zaxxer" % "HikariCP" % "6.3.0",

      "org.slf4j" % "slf4j-simple" % "2.0.16"
    ),

    // sbt-revolver forks reStart's JVM with cwd = (baseDirectory in reStart), which defaults to
    // this subproject's own directory (server/). Point it at the repo root instead, since that's
    // where .env/ (see Secrets.scala) and docker-compose.yaml live, and where commands are
    // documented to be run from.
    reStart / baseDirectory := (ThisBuild / baseDirectory).value,

    // Self-contained jar for deployment: `sbt awServer/assembly` -> server/target/scala-3.5.2/awServer.jar
    assembly / mainClass := Some("fivedomains.server.Main"),
    assembly / assemblyJarName := "awServer.jar",
    assembly / assemblyMergeStrategy := {
      // xnio/undertow (pulled in transitively by cask) discover their providers via
      // META-INF/services -- these need concatenating, not discarding, or startup fails with
      // "No XNIO provider found".
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

// The previous back-end (zio-http + quill-jdbc-zio) was moved aside to server-legacy-zio/
// and is intentionally not built. See CLAUDE.md for why.
// lazy val awServerLegacyZio = project.in(file("server-legacy-zio"))
//   .dependsOn(commonJVM)
//   .settings(
//     libraryDependencies ++= Seq(
//       "dev.zio" %% "zio-http" % "3.0.0-RC2",
//       "io.getquill" %% "quill-jdbc-zio" % "4.6.0.1",
//       "org.postgresql"       %  "postgresql"     % "42.3.1",
//       "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.20.0"
//     ),
//     excludeDependencies ++= Seq(
//       "com.lihaoyi" % "geny_2.13"
//     )
//   )


