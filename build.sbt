organization in ThisBuild := "de.beuth"
version in ThisBuild := "1.0-SNAPSHOT"

// the Scala version that will be used for cross-compiled libraries
scalaVersion in ThisBuild := "2.11.11"

val macwire = "com.softwaremill.macwire" %% "macros" % "2.2.5" % "provided"
val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1" % Test
val playJsonDerivedCodecs = "org.julienrf" %% "play-json-derived-codecs" % "4.0.0"
val scalaScraper = "net.ruippeixotog" %% "scala-scraper" % "2.0.0-RC2"
val selenium = "org.seleniumhq.selenium" % "selenium-java" % "3.4.0"


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
      lagomScaladslKafkaClient,
      macwire,
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`gatherbox-api`, `scan-impl`,`scan-api`)

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

lazy val `scanner-commons`= (project in file("scanner-commons"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      lagomScaladslApi
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
  ).dependsOn(`scanner-commons`)

lazy val `scan-impl` = (project in file("scan-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslTestKit,
      lagomScaladslKafkaBroker,
      macwire,
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`,`censys-scanner-api`, `ixquick-scanner-api`, `profile-scanner-api`, `utils`, `scanner-commons`)


lazy val `censys-scanner-api`= (project in file("censys-scanner-api"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslServer % Optional,
      playJsonDerivedCodecs,
      scalaTest
    )
  ).dependsOn(`censys-api`, `scanner-commons`)

lazy val `censys-scanner-impl` = (project in file("censys-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslTestKit,
      lagomScaladslKafkaBroker,
      macwire,
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `utils`, `censys-api`, `censys-scanner-api`,  `scanner-commons`)

lazy val `ixquick-scanner-api`= (project in file("ixquick-scanner-api"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslServer % Optional,
      playJsonDerivedCodecs,
      scalaTest
    )
  ).dependsOn(`scanner-commons`)

lazy val `ixquick-scanner-impl` = (project in file("ixquick-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslTestKit,
      lagomScaladslKafkaBroker,
      scalaScraper,
      macwire,
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `utils`,`scanner-commons`, `ixquick-scanner-api`, `proxybrowser-impl`, `proxybrowser-api`)

lazy val `proxybrowser-api`= (project in file("proxybrowser-api"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslServer % Optional,
      playJsonDerivedCodecs,
      scalaTest
    )
  )

lazy val `proxybrowser-impl` = (project in file("proxybrowser-impl"))
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
  .dependsOn(`proxybrowser-api`, `utils`)

lazy val `profile-scanner-api`= (project in file("profile-scanner-api"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslServer % Optional,
      playJsonDerivedCodecs,
      scalaTest
    )
  ).dependsOn(`scanner-commons`)

lazy val `profile-scanner-impl` = (project in file("profile-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslTestKit,
      lagomScaladslKafkaBroker,
      scalaScraper,
      macwire,
      scalaTest,
      selenium
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `ixquick-scanner-api`, `utils`,`scanner-commons`, `profile-scanner-api`, `proxybrowser-impl`)

/**
  * Cleanup on start to not have events / data in queue
  */
lagomKafkaCleanOnStart in ThisBuild := true
lagomCassandraCleanOnStart in ThisBuild := true

/**
  * Use external kafka server
  *
  * Server configuration can be found at:
  *
  * OSX installed with homebrew: /usr/local/Cellar/kafka/0.11.0.0/libexec/config/server.properties
  *
  */
lagomKafkaEnabled in ThisBuild := true
//lagomKafkaPort in ThisBuild := 9092

/**
  * External Sevices
  */
lagomUnmanagedServices in ThisBuild := Map("censys" -> "https://www.censys.io:443")

/**
  * Cached update resolution
  */
//updateOptions := updateOptions.value.withCachedResolution(true)