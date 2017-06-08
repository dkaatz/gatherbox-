organization in ThisBuild := "de.beuth"
version in ThisBuild := "1.0-SNAPSHOT"

// the Scala version that will be used for cross-compiled libraries
scalaVersion in ThisBuild := "2.11.8"

val macwire = "com.softwaremill.macwire" %% "macros" % "2.2.5" % "provided"
val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1" % Test
val playJsonDerivedCodecs = "org.julienrf" %% "play-json-derived-codecs" % "3.3"



lazy val `gatherbox` = (project in file("."))
  .aggregate(`gatherbox-api`, `gatherbox-impl`, `gatherbox-stream-api`, `gatherbox-stream-impl`, `censys-api`)


/**
  * PREDEFINED START
  */
lazy val `gatherbox-api` = (project in file("gatherbox-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `gatherbox-impl` = (project in file("gatherbox-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslTestKit,
      macwire,
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`gatherbox-api`, `censys-api`)

lazy val `gatherbox-stream-api` = (project in file("gatherbox-stream-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `gatherbox-stream-impl` = (project in file("gatherbox-stream-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslTestKit,
      macwire,
      scalaTest
    )
  )
  .dependsOn(`gatherbox-stream-api`, `gatherbox-api`)

/**
  * PREDEFINED END
  */


lazy val `utils`= (project in file("utils"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslServer % Optional,
      playJsonDerivedCodecs,
      scalaTest
    )
  )
//
lazy val `censys-api` = (project in file("censys-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `scan-api` = (project in file("scan-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`censys-api`)

lazy val `scan-impl` = (project in file("scan-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslTestKit,
      macwire,
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`censys-api`, `scan-api`, `utils`)


lagomUnmanagedServices in ThisBuild := Map("censys" -> "https://www.censys.io:443")