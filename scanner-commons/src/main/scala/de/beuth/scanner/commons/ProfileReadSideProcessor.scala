package de.beuth.scanner.commons

import akka.Done
import com.datastax.driver.core.{PreparedStatement, Row}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEventTag, ReadSideProcessor}
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class ProfileRepository(scanner: String, session: CassandraSession)(implicit ec: ExecutionContext) {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileRepository])

  def getProfilesByUrls(urls: Seq[String]): Future[Seq[Profile]] = {
    log.debug(s"SELECT JSON * FROM ${scanner}Profiles WHERE url IN (${urls.mkString("'", "',", "'")})")
    session.selectAll(
      s"""
         SELECT JSON * FROM ${scanner}Profiles WHERE url IN (${urls.mkString("'", "',", "'")})
       """).map {
       rowSeq => {
         log.debug(s"QUERY RESULTS: ${rowSeq.length}")
         rowSeq.map(row => rowToProfile(row))
           .filter(_.isDefined)
           .map(_.get)
       }
    }
  }

  def getProfileByUrl(url: String): Future[Option[Profile]] = {
    session.selectOne(s"""
      SELECT JSON * FROM ${scanner}Profiles
      WHERE url = ?
    """, url).map {
      case Some(row) => rowToProfile(row)
      case None => None
    }
  }

  private def rowToProfile(row: Row): Option[Profile] = {
    val jsonValue: JsValue = Json.parse(row.getString("[json]"))
    val profileFromJson: JsResult[Profile] = Json.fromJson[Profile](jsonValue)

    profileFromJson match {
      case JsSuccess(p: Profile, path: JsPath) => Some(p)
      case e: JsError =>{
        log.error(s"Could not transform value for ${row.toString} --> ${e.toString}")
        None
      }
    }
  }
}

class ProfileEventProcessor(scanner: String, session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext)
 extends ReadSideProcessor[ScannerEvent] {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileEventProcessor])
  private var createProfileStatement: PreparedStatement = null

  def buildHandler = {
    readSide.builder[ScannerEvent](s"${scanner}ScannerEventOffset")
      .setGlobalPrepare(createTables)
      .setPrepare(_ => prepareStatements())
      .setEventHandler[ProfileScanned](e => createProfile(e.event.profile))
      .build
  }

  override def aggregateTags: Set[AggregateEventTag[ScannerEvent]] = Set(ScannerEvent.Tag)

  def createTables() =
    for {
      // creating user defined type
      _ <- session.executeCreateTable("""
        CREATE TYPE IF NOT EXISTS jobExperience (
          title text,
          company text,
          started text,
          ended text,
          description text,
          isCurrent boolean
        )
      """)
    //creating profiles table
      _ <- session.executeCreateTable(s"""
        CREATE TABLE IF NOT EXISTS ${scanner}Profiles (
          url text PRIMARY KEY,
          scanned boolean,
          updatedat text,
          firstname text,
          lastname text,
          skills set<text>,
          exp set<frozen <jobExperience>>
        )
      """)
    } yield Done


  private def createProfile(profile: Profile) = {
    log.info(s"Read Side Processor received new Profile and persisting it. ${profile.toString}")
    Future.successful(scala.collection.immutable.Seq(createProfileStatement.bind(Json.toJson[Profile](profile).toString())))
  }



  private def prepareStatements() =
    for {
      insertProfile <- session.prepare(s"""
        INSERT INTO ${scanner}Profiles JSON ? USING TTL 604800
      """)
    } yield {
      createProfileStatement = insertProfile
      Done
    }
}