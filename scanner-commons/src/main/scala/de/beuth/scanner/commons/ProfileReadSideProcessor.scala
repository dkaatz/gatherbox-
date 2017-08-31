package de.beuth.scanner.commons
import akka.Done
import com.datastax.driver.core.{PreparedStatement, Row}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEventTag, ReadSideProcessor}
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
/**
  * Supertype for Read-Side Entities of Profile Scanners
  *
  * This is implemented to avoid scanning the same profile in multiple scans, so we just store them in a read side
  *
  * enabling the profile scanners to search for the profile here first and just scann if it does not exist
  */


/**
  *
  * @param scanner name of the scanner
  * @param session cassandra session
  */
class ProfileRepository(scanner: String, session: CassandraSession)(implicit ec: ExecutionContext) {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileRepository])

  /**
    * Get Profiles with a WHERE IN query
    * @param urls list of urls
    * @return list of profiles found
    */
  def getProfilesByUrls(urls: Seq[String]): Future[Seq[Profile]] = {
    log.info(s"SELECT JSON * FROM ${scanner}Profiles WHERE url IN (${urls.mkString("'", "','", "'")})")
    session.selectAll(
      s"""
         SELECT JSON * FROM ${scanner}Profiles WHERE url IN (${urls.mkString("'", "','", "'")})
       """).map {
       rowSeq => {
         log.debug(s"QUERY RESULTS: ${rowSeq.length}")
         rowSeq.map(row => rowToProfile(row))
           .filter(_.isDefined)
           .map(_.get)
       }
    }
  }

  /**
    * Gets a single profile by url or none
    * @param url url of the profile
    * @return some profile or none
    */
  def getProfileByUrl(url: String): Future[Option[Profile]] = {
    session.selectOne(s"""
      SELECT JSON * FROM ${scanner}Profiles
      WHERE url = ?
    """, url).map {
      case Some(row) => rowToProfile(row)
      case None => None
    }
  }

  /**
    * Reads a row and transforms it to a Profile if possible
    */
  private def rowToProfile(row: Row): Option[Profile] = {
    //we read the [json] column wich is returned because we query with SELECT JSON
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

/**
  * This class handles updating the read side based on the write side events
  */
class ProfileEventProcessor(scanner: String, session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext)
 extends ReadSideProcessor[ScannerEvent] {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileEventProcessor])
  private var createProfileStatement: PreparedStatement = null

  /**
    * The handler with the offset prefixed by the provieded scanner name is calling the create tables statement
    * preparing the statments and listening for ProfileScanned events on the write side entity to create an entry here
    */
  def buildHandler = {
    readSide.builder[ScannerEvent](s"${scanner}ScannerEventOffset")
      .setGlobalPrepare(createTables)
      .setPrepare(_ => prepareStatements())
      .setEventHandler[ProfileScanned](e => createProfile(e.event.profile))
      .build
  }

  //aggregate tag of the event to listen for
  override def aggregateTags: Set[AggregateEventTag[ScannerEvent]] = Set(ScannerEvent.Tag)


  /**
    * Creates the User defined type "jobexperience" and the profiles table
    */
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


  /**
    * Binds the profile to the insertion query
    * @param profile profile to persist
    */
  private def createProfile(profile: Profile) = {
    log.info(s"Read Side Processor received new Profile and persisting it. ${profile.toString}")
    Future.successful(scala.collection.immutable.Seq(createProfileStatement.bind(Json.toJson[Profile](profile).toString())))
  }


  /**
    * Prepares the insertion statment
    */
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
