organization in ThisBuild := "de.beuth"
version in ThisBuild := "1.0-SNAPSHOT"

// the Scala version that will be used for cross-compiled libraries
scalaVersion in ThisBuild := "2.11.11"

val macwire = "com.softwaremill.macwire" %% "macros" % "2.2.5" % "provided"
val playJsonDerivedCodecs = "org.julienrf" %% "play-json-derived-codecs" % "4.0.0"
val scalaScraper = "net.ruippeixotog" %% "scala-scraper" % "2.0.0-RC2"
val selenium = "org.seleniumhq.selenium" % "selenium-java" % "3.4.0"

lazy val `utils`= (project in file("utils"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  )

lazy val `scanner-commons`= (project in file("scanner-commons"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslKafkaBroker,
      lagomScaladslPersistenceCassandra
    )
  ).dependsOn(`utils`)

lazy val `censys-api` = (project in file("censys-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `scan-api` = (project in file("scan-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  ).dependsOn(`scanner-commons`)

lazy val `scan-impl` = (project in file("scan-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      macwire
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `linkedin-scanner-api`,`censys-scanner-api`, `ixquick-scanner-api`, `xing-scanner-api`, `databreach-api`,`utils`, `scanner-commons`)


lazy val `censys-scanner-api`= (project in file("censys-scanner-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`censys-api`, `scanner-commons`)

lazy val `censys-scanner-impl` = (project in file("censys-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      macwire
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `utils`, `censys-api`, `censys-scanner-api`,  `scanner-commons`)

lazy val `ixquick-scanner-api`= (project in file("ixquick-scanner-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`scanner-commons`)

lazy val `ixquick-scanner-impl` = (project in file("ixquick-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      scalaScraper,
      macwire
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `utils`,`scanner-commons`, `ixquick-scanner-api`, `proxybrowser-impl`, `proxybrowser-api`)

lazy val `proxybrowser-api`= (project in file("proxybrowser-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      lagomScaladslServer % Optional,
      playJsonDerivedCodecs
    )
  )

lazy val `proxybrowser-impl` = (project in file("proxybrowser-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      macwire
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`proxybrowser-api`, `utils`)

lazy val `xing-scanner-api`= (project in file("xing-scanner-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`scanner-commons`)

lazy val `xing-scanner-impl` = (project in file("xing-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      scalaScraper,
      macwire
    )
  )
  .settings(lagomForkedTestSettings: _*)
  .dependsOn(`scan-api`, `ixquick-scanner-api`, `utils`,`scanner-commons`, `xing-scanner-api`, `proxybrowser-impl`)


lazy val `linkedin-scanner-api`= (project in file("linkedin-scanner-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`scanner-commons`)


lazy val `linkedin-scanner-impl` = (project in file("linkedin-scanner-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      macwire,
      selenium
    )
  )
  .dependsOn(`scan-api`, `ixquick-scanner-api`, `utils`,`scanner-commons`, `linkedin-scanner-api`, `proxybrowser-impl`)


lazy val `databreach-api`= (project in file("databreach-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`scanner-commons`, `databreach-scanner-api`)


lazy val `databreach-impl` = (project in file("databreach-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      macwire
    )
  )
  .dependsOn(`scan-api`, `databreach-api` ,`linkedin-scanner-api`, `xing-scanner-api`, `utils`,`scanner-commons`, `databreach-scanner-api`)


lazy val `databreach-scanner-api`= (project in file("databreach-scanner-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi,
      playJsonDerivedCodecs
    )
  ).dependsOn(`scanner-commons`)


lazy val `scan-result-api`= (project in file("scan-result-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  ).dependsOn(`databreach-api`, `censys-scanner-api`, `scanner-commons`)

lazy val `scan-result-impl` = (project in file("scan-result-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaClient,
      macwire
    )
  )
  .dependsOn(`scan-result-api`, `databreach-api` ,`censys-scanner-api`, `linkedin-scanner-api`, `xing-scanner-api`)


/**
  * Cleanup on start to not have events / data in qeue
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
//lagomKafkaEnabled in ThisBuild := true
//lagomKafkaPort in ThisBuild := 9092

/**
  * External Sevices
  */
lagomUnmanagedServices in ThisBuild := Map(
  "censys" -> "https://www.censys.io:443"
)

/**
  * Cached update resolution
  */
//updateOptions := updateOptions.value.withCachedResolution(true)