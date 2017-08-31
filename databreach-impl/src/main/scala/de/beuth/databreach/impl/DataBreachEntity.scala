package de.beuth.databreach.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuh.databreach.api.DataBreachCombinedResults
import de.beuth.databreach.scanner.api.{DataBreachResult, DataBreachResults}
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.scanner.commons.{ScanFinished, ScannerUpdateEvent, _}
import de.beuth.xing.scanner.api.XingScannerService
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * The write site entity of the service
  */
class DataBreachEntity  extends ScannerEntity {

  //we initalize with this state containing nothing.
  override def initialState: DataBreachState = DataBreachState(None, false, false, false, Seq())

  override def behavior: Behavior = scanStatusBehavior.orElse(
    Actions()
      .onCommand[StartCollection, Done] {
        case (StartCollection(serviceName), ctx, state: DataBreachState) =>
          ctx.thenPersist(CollectionStarted(serviceName)) {
            _ => ctx.reply(Done)
          }
      }.onCommand[CompleteCollection, Done] {
        case (CompleteCollection(serviceName), ctx, state: DataBreachState) =>
          ctx.thenPersist(CollectionCompleted(serviceName)) {
            _ => ctx.reply(Done)
          }
      }.onCommand[AddFirstnameToScan, Done] {
        case (AddFirstnameToScan(url, firstname, lastname), ctx, state: DataBreachState) =>
          ctx.thenPersist(
            FirstnameToScanAdded(url, firstname, lastname)
          ) {
            _ => ctx.reply(Done)
          }
     }.onCommand[AddLastnameToScan, Done] {
      case (AddLastnameToScan(url, firstname, lastname), ctx, state: DataBreachState) =>
        ctx.thenPersist(
          LastnameToScanAdded(url, firstname, lastname)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateBreachCombined, Done] {

      //its the last breach so we also need trigger the scan finished
      case (UpdateBreachCombined(combinedResults), ctx, state: DataBreachState) if isLastBreach(combinedResults.url, state) =>
        ctx.thenPersistAll(
          BreachCombinedUpdated(combinedResults),
          ScanFinished(Instant.now())
        ) {
          case _ => ctx.reply(Done)
        }

      //its  not the last breach so we just persist the BreachCombinedUpdated event
      case (UpdateBreachCombined(combinedResults), ctx, state: DataBreachState) =>
        ctx.thenPersist(
          BreachCombinedUpdated(combinedResults)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateBreach, Done] {

      //if the scan got not added with addToScan we refuse the update
      case (UpdateBreach(breachResults), ctx, state: DataBreachState) if !isInState(breachResults.url, state) =>
        ctx.invalidCommand(s"Not added to scan ${breachResults.toString}, so it can not get updated")
        ctx.done

      //if its the last scan we persist additionally the ScanFinished event to finish the scanner
      case (UpdateBreach(breachResults), ctx, state: DataBreachState) if isLastBreach(breachResults.url, state) =>
        val event = deriveUpdateEvent(breachResults, state)
        //if the derived event results in a BreachCombinedUpdated we persist also the BreachUpdated event
        // this is done because we need to store both events to also trigger the read side update of storing matches per name
        if(event.isInstanceOf[BreachCombinedUpdated])
          ctx.thenPersistAll(
            BreachUpdated(breachResults),
            event,
            ScanFinished(Instant.now())
          ) {
            case _ => ctx.reply(Done)
          }
        else
          ctx.thenPersist(
            event
          ) {
            _ => ctx.reply(Done)
          }
      case (UpdateBreach(breachResults), ctx, state: DataBreachState) =>
        val event = deriveUpdateEvent(breachResults, state)
        //if the derived event results in a BreachCombinedUpdated we persist also the BreachUpdated event
        // this is done because we need to store both events to also trigger the read side update of storing matches per name
         if(event.isInstanceOf[BreachCombinedUpdated])
          ctx.thenPersistAll(
            BreachUpdated(breachResults),
            event
          ) {
            case _ => ctx.reply(Done)
          }
         else
           ctx.thenPersist(
             event
           ) {
             _ => ctx.reply(Done)
           }

    }.onCommand[ScanBreachFailure, Done] {
      //was never added to scan so we can not remove it
      case (ScanBreachFailure(url), ctx, state: DataBreachState) if !isInState(url, state) =>
        ctx.invalidCommand(s"Not added to scan $url, so it can not get removed")
        ctx.done
        //if its the last one we also finish the scan
      case (ScanBreachFailure(url), ctx, state: DataBreachState) if isLastBreach(url, state) =>
        ctx.thenPersistAll(
          FailedScanBreach(url),
          ScanFinished(Instant.now())
        ) {
          case _ => ctx.reply(Done)
        }
        //normal case we jsut remove it
      case (ScanBreachFailure(url), ctx, state: DataBreachState) =>
        ctx.thenPersist(
          FailedScanBreach(url)
        ) {
          _ => ctx.reply(Done)
        }
    }.onEvent {
      /**
        * Handling of events, the corresponding state changes get called
        */
      case (CollectionCompleted(serviceName), state: DataBreachState) => state.collected(serviceName)
      case (FirstnameToScanAdded(url, firstname, lastname), state: DataBreachState) => state.addToScan(url, firstname, lastname)
      case (LastnameToScanAdded(url, firstname, lastname), state: DataBreachState) => state.addToScan(url, firstname, lastname)
      case (BreachUpdated(dataBreachResults), state: DataBreachState) => state.addBreachResult(dataBreachResults)
      case (BreachCombinedUpdated(combinedResults), state: DataBreachState) => state.addCombinedResult(combinedResults)
      case (FailedScanBreach(url), state: DataBreachState) => state.removeToScan(url)
      case (CollectionStarted(serviceName), state: DataBreachState) => state.isCollecting(serviceName)
    }
  )

  /**
    * This helper method derives the update event to use by looking into the existing entry.
    * If the entry already got results its the second update event and the entry is finished
    *
    * @param breachResults
    * @param state
    * @return
    */
  private def deriveUpdateEvent(breachResults: DataBreachResults, state: DataBreachState): ScannerUpdateEvent =  {
    val entry = state.results.filter(_.url == breachResults.url).head
    if(entry.results.isEmpty)
      BreachUpdated(breachResults)
    else
      BreachCombinedUpdated(
        DataBreachCombinedResults(
          url = breachResults.url,
          firstname = entry.firstname,
          lastname = entry.lastname,
          scanned = true,
          results = (entry.results ++ breachResults.results).distinct
        )
      )
  }

  private def isInState(url: String, state: DataBreachState): Boolean = !state.results.filter(_.url == url).isEmpty

  private def isLastBreach(url: String, state: DataBreachState): Boolean =
    state.linkedinCollected && state.xingCollected && state.results.filterNot(_.scanned).length == 1 && state.results.filterNot(_.scanned).head.url == url
}

/**
  * The state of the scanner
  *
  * @param startedat when did the scanner started
  * @param finished did he finished?
  * @param xingCollected has xing collected?
  * @param linkedinCollected has linkedin collected?
  * @param results the results of the scan
  */
case class DataBreachState(startedat: Option[Instant],
                           finished: Boolean,
                           xingCollected: Boolean,
                           linkedinCollected: Boolean,
                           results: Seq[DataBreachCombinedResults]) extends ScannerState {

  /**
    * Initializes the list again
    */
  override def start(timestamp: Instant): DataBreachState =
    copy(
      startedat = Some(timestamp),
      finished = false,
      linkedinCollected = true,
      xingCollected = true,
      results = Seq())

  override def finish(): DataBreachState = copy(finished = true)

  /**
    * Adding a result from a scan by the data breach scanner service
    */
  def addBreachResult(dbr: DataBreachResults): DataBreachState = {
    val entry = results.filter(_.url == dbr.url).head
    val idx = results.indexWhere(_.url == dbr.url)
    copy(results = results.updated(idx, DataBreachCombinedResults(entry.url, entry.firstname, entry.lastname, false, emptyListHelper(dbr.results))))
  }

  //we add an list with an empty result to still be able to recognize when the 2nd event occures
  def emptyListHelper(list: Seq[DataBreachResult]): Seq[DataBreachResult] =
    if(list.isEmpty) {
      Seq(DataBreachResult("",""))
    } else {
      list
    }


  /**
    * Adding a result to the scanner for the second time (combined result
    */
  def addCombinedResult(dbcr: DataBreachCombinedResults): DataBreachState = {
    val idx = results.indexWhere(_.url == dbcr.url)
    if(idx == -1)
      copy(results = results :+ dbcr)
    else
      copy(results = results.updated(idx, dbcr))
  }

  /**
    * Adding profile to get scanned by the external scanner
    *
    * @param url url of profile
    * @param firstname firstname of owner
    * @param lastname lastname of owner
    * @return
    */
  def addToScan(url: String, firstname: String, lastname: String): DataBreachState =
  //we do nothing if it aleady exists, otherwise we add it
    if(results.exists(_.url == url))
      copy()
    else
      copy(results = results :+ DataBreachCombinedResults(url, firstname, lastname, false, Seq()))

  //removing an entry in case the scan failed
  def removeToScan(url: String): DataBreachState = copy(results = results.filterNot(_.url == url))

  //mark profile collection end by service name
  def collected(serviceName: String) = serviceName match {
    case LinkedinScannerService.NAME => copy(linkedinCollected = true)
    case XingScannerService.NAME => copy(xingCollected = true)
  }

  //mark profile collection start  by service name
  def isCollecting(serviceName: String) = serviceName match {
    case LinkedinScannerService.NAME => copy(linkedinCollected = false)
    case XingScannerService.NAME => copy(xingCollected = false)
  }
}
//serializer / deserializer
object DataBreachState {
  implicit val format: Format[DataBreachState] = Json.format
}

/**
 * Commands
 */
case class AddFirstnameToScan(url: String, firstname: String, lastname: String) extends ScannerCommand with ReplyType[Done]
object AddFirstnameToScan {
  implicit val format: Format[AddFirstnameToScan] = Json.format
}

case class AddLastnameToScan(url: String, firstname: String, lastname: String) extends ScannerCommand with ReplyType[Done]
object AddLastnameToScan {
  implicit val format: Format[AddLastnameToScan] = Json.format
}

case class ScanBreachFailure(url: String) extends ScannerCommand with ReplyType[Done]
object ScanBreachFailure {
  implicit val format: Format[ScanBreachFailure] = Json.format
}

case class UpdateBreachCombined(results: DataBreachCombinedResults) extends ScannerCommand with ReplyType[Done]
object UpdateBreachCombined {
  implicit val format: Format[UpdateBreachCombined] = Json.format
}

case class UpdateBreach(results: DataBreachResults) extends ScannerCommand with ReplyType[Done]
object UpdateBreach {
  implicit val format: Format[UpdateBreach] = Json.format
}

case class CompleteCollection(serviceName: String) extends ScannerCommand with ReplyType[Done]
object CompleteCollection {
  implicit val format: Format[CompleteCollection] = Json.format
}

case class StartCollection(serviceName: String) extends ScannerCommand with ReplyType[Done]
object StartCollection {
  implicit val format: Format[StartCollection] = Json.format
}
/**
  * Events
  */
case class FirstnameToScanAdded(url: String, firstname: String, lastname: String) extends ScannerUpdateEvent
object FirstnameToScanAdded {
  implicit val format: Format[FirstnameToScanAdded] = Json.format
}

case class LastnameToScanAdded(url: String, firstname: String, lastname: String) extends ScannerUpdateEvent
object LastnameToScanAdded {
  implicit val format: Format[LastnameToScanAdded] = Json.format
}

case class FailedScanBreach(url: String) extends ScannerUpdateEvent
object FailedScanBreach {
  implicit val format: Format[FailedScanBreach] = Json.format
}

case class BreachCombinedUpdated(results: DataBreachCombinedResults) extends ScannerUpdateEvent
object BreachCombinedUpdated {
  implicit val format: Format[BreachCombinedUpdated] = Json.format
}

case class BreachUpdated(results: DataBreachResults) extends ScannerUpdateEvent
object BreachUpdated {
  implicit val format: Format[BreachUpdated] = Json.format
}

case class CollectionCompleted(serviceName: String) extends ScannerUpdateEvent
object CollectionCompleted {
  implicit val format: Format[CollectionCompleted] = Json.format
}

case class CollectionStarted(serviceName: String) extends ScannerUpdateEvent
object CollectionStarted {
  implicit val format: Format[CollectionStarted] = Json.format
}

object DataBreachScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[DataBreachState],
    JsonSerializer[DataBreachResults],
    JsonSerializer[DataBreachResult],
    JsonSerializer[FirstnameToScanAdded],
    JsonSerializer[LastnameToScanAdded],
    JsonSerializer[ScanBreachFailure],
    JsonSerializer[UpdateBreach],
    JsonSerializer[AddFirstnameToScan],
    JsonSerializer[AddLastnameToScan],
    JsonSerializer[FailedScanBreach],
    JsonSerializer[BreachUpdated],
    JsonSerializer[CollectionCompleted],
    JsonSerializer[BreachCombinedUpdated],
    JsonSerializer[UpdateBreachCombined],
    JsonSerializer[DataBreachCombinedResults],
    JsonSerializer[CompleteCollection]
  ) ++ ScannerSerialzierRegistry.serializers
}