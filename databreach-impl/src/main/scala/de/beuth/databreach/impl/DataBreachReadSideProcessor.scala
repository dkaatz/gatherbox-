package de.beuth.databreach.impl

import akka.Done
import com.datastax.driver.core.PreparedStatement
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEventTag, ReadSideProcessor}
import de.beuth.databreach.scanner.api.{DataBreachResult, DataBreachResults}
import de.beuth.scanner.commons.{ScannerEvent}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future};

/**
  * THe repository to query the read side
  */
class DataBreachReposiotry(session: CassandraSession)(implicit ec: ExecutionContext) {

  private final val log: Logger = LoggerFactory.getLogger(classOf[DataBreachReposiotry])

  /**
    * Tries to find a breach with the provided name
    *
    * Returns either some breach results or none
    *
    * @param name name to search for
    */
  def findBreach(name: String): Future[Option[DataBreach]] =
    session.selectOne(
      s"""
         SELECT JSON * FROM dataBreachResults WHERE name = ?
       """, name
    ).map {
      case Some(row) => {
        val jsonValue: JsValue = Json.parse(row.getString("[json]"))
        val profileFromJson: JsResult[DataBreach] = Json.fromJson[DataBreach](jsonValue)

        profileFromJson match {
          case JsSuccess(db: DataBreach, path: JsPath) => Some(db)
          case e: JsError =>{
            log.error(s"Could not transform value for ${row.toString} --> ${e.toString}")
            None
          }
        }
      }
      case None => None
    }


}

/**
  * The event processor to update the read side based on the write side events
  */
class DataBreachEventProcessor(session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext)
  extends ReadSideProcessor[ScannerEvent] {

  private final val log: Logger = LoggerFactory.getLogger(classOf[DataBreachEventProcessor])
  private var createBreachStatement: PreparedStatement = null

  /**
    * The build handler of the entity
    *
    * the offset is called: dataBreachOffset

    * we just creat one table and one udt
    *
    * and we create new enties on "BreachUpdated" events
    *
    * @return
    */
  def buildHandler = {
    readSide.builder[ScannerEvent](s"dataBreachOffset")
      .setGlobalPrepare(createTables)
      .setPrepare(_ => prepareStatements())
      .setEventHandler[BreachUpdated](e => createBreachEntry(e.event.results))
      .build
  }

  //the tag used
  override def aggregateTags: Set[AggregateEventTag[ScannerEvent]] = Set(ScannerEvent.Tag)

  /**
    * Creating UDT  and table
    */
  def createTables() =
    for {
    // creating user defined type
      _ <- session.executeCreateTable(
        """
        CREATE TYPE IF NOT EXISTS dataBreachResult (
          email text,
          password text
        )
        """)
      //creating profiles table
      _ <- session.executeCreateTable(
        s"""
        CREATE TABLE IF NOT EXISTS dataBreachResults (
        name text PRIMARY KEY,
        results set<frozen <dataBreachResult>>,
        )
        """)
    } yield Done


  /**
    * The value binding for the insert statement
    *
    * @param entry DataBreachResults received
    */
  private def createBreachEntry(entry: DataBreachResults) = {
    log.info(s"Read Side Processor received new Profile and persisting it. ${entry.toString}")
    Future.successful(scala.collection.immutable.Seq(
      createBreachStatement.bind(Json.toJson[DataBreach](DataBreach(entry.name, results = Some(entry.results))).toString()))
    )
  }


  /**
    * Preparing the insert statment
    */
  private def prepareStatements() =
    for {
      createBreach <- session.prepare(
        s"""
        INSERT INTO dataBreachResults JSON ?
        """)
    } yield {
      createBreachStatement = createBreach
      Done
    }
}

/**
  * The entry of  data breach results for a single name
  */
case class DataBreach(name: String, results: Option[Seq[DataBreachResult]])
object DataBreach {
  implicit val format: Format[DataBreach] = Json.format
}