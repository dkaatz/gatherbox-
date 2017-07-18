package de.beuth.scan.impl

import java.time.{Instant, LocalDateTime}

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.scan.api.ScannerStatus
import de.beuth.utils.JsonFormats._
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * ScanEntity is the persiten write side entity for the ScanService and represents the state of a scan by keyword
  *
  * Primary Identifier: keyword to scan
  */
class ScanEntity extends PersistentEntity {
  override type Command = ScanCommand
  override type Event = ScanEvent
  override type State = Scan

  override def initialState: Scan = Scan(None , Seq[ScannerStatus]())

  /**
    * Describes the behavior of the persistent entity:
    *
    * StartScan is a valid command when:
    *   - last scan is finished
    *   - or it is the first scan
    *
    * StartScanner  is a valid command when:
    *   - a scanner with the given name exists
    *   - and the scanner is not running
    *
    * FinishScanner is a valid command when:
    *   - a scanner with the given name exists
    *   - the scanner is not finished yet and got started
    *
    * When FinishScanner finishes the last Scanner emit also the ScanFinished Event
    *
    * @todo properly implenment scanfailure
    *
    *
    */
  override def behavior: Behavior = Actions()
    .onCommand[StartScan, Done] {

    //scan never run or finished
    case (StartScan(startedAt), ctx, state) if state.startedAt.isEmpty || state.scanner.filter(_.finished == false).isEmpty =>
      ctx.thenPersist(ScanStarted(startedAt)){ _ => ctx.reply(Done)}

    //scan is still running ( not all expected services finished the scan yet )
    case (StartScan(startedAt), ctx:ScanEntity.this.CommandContext[Done], state) => {
      ctx.invalidCommand(s"Scan for $entityId is already running.")
      ctx.done
    }

  }.onCommand[StartScanner, Done] {

    //scanner does not exist
    case (StartScanner(name, timestamp), ctx, state) if state.scanner.filter(_.name == name).isEmpty => {
      ctx.invalidCommand(s"Scanner <$name> does not exist.")
      ctx.done
    }

    //scanner already started and still running
    case (StartScanner(name, timestamp), ctx, state)
      if state.scanner.filter(_.name == name)(0).startedAt.isEmpty && state.scanner.filter(_.name == name).filter(_.finished).isEmpty => {
      ctx.invalidCommand(s"Scanner <$name> can not start twice.")
      ctx.done
    }

    //valid startScanner command
    case (StartScanner(name, timestamp), ctx, state) =>
      ctx.thenPersist(ScannerStarted(name, timestamp)) { _ => ctx.reply(Done)}

  }.onCommand[FinishScanner, Done] {

    //scanner does not exist
    case (FinishScanner(name), ctx, state) if  state.scanner.filter(_.name == name).isEmpty =>
      ctx.invalidCommand(s"Scanner <$name> does not exist.")
      ctx.done

    //scanner already finsihed
    case (FinishScanner(name), ctx, state) if state.scanner.filter(_.name == name).filter(_.finished).isEmpty =>
      ctx.invalidCommand(s"Scanner <$name> can not finish twice.")
      ctx.done

    //last scanner finished
    case (FinishScanner(name), ctx, state) if  state.scanner.filter(_.finished).size == state.scanner.filter(_.name == name).filter(_.finished) == 1  =>
      ctx.thenPersistAll(
        ScannerFinsihed(name),
        ScanFinished(Instant.now())
      ) {
        case _ => ctx.reply(Done)
      }
    //intermediate scanner finished
    case (FinishScanner(name), ctx, state) =>
      ctx.thenPersist(ScannerFinsihed(name)) { _ => ctx.reply(Done)}

    //@todo think about ScanFailed -> should break all pending scans and reset the entity to the last successfull scan
    //      maybe its better to wait for any scanner failed command and break all other scans then
    //      in any case a failed scan should not block further scan attempts
  }.onCommand[FailedScan, Done] {
    case (FailedScan(timestamp, errorMsg), ctx, state) =>
      ctx.thenPersist(
        ScanFailed(timestamp, errorMsg)
      ) {
        _ => ctx.reply(Done)
      }
  }.onEvent {
    /**
      * Evetns that change the state of the entity
      */
    case (ScanStarted(timestamp), scan) => scan.startScan(timestamp)
    case (ScannerFinsihed(name), scan) => scan.finishScanner(name)
    case (ScannerStarted(name, timestamp), scan) => scan.startScanner(name, timestamp)

    /**
      * Events that do not change the state, used only for inter process communication
      */
    case (ScanFinished(timestamp), scan) => scan
    case (ScanFailed(timestamp, errorMsg), scan) => scan
  }.orElse(getScan)


  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] {  case (GetScan, ctx, state) => ctx.reply(state) }
}

/**
  *
  * The current state held by the persistent entity.
  * It holds the last time the scanner started and the state of each scanner
  *
  * @param startedAt time the last scan got started
  * @param scanner list of scanners states
  */
case class Scan(startedAt: Option[Instant], scanner: Seq[ScannerStatus]) {

  def startScanner(name: String, timestamp: Instant): Scan =
    copy(scanner = scanner.updated(indexOfScanner(name), ScannerStatus(name, Some(timestamp), false)))


  def finishScanner(name:String): Scan =
    copy(scanner = scanner.updated(indexOfScanner(name), scanner(indexOfScanner(name)).copy(finished = true)))


  def indexOfScanner(name:String): Int = scanner.indexWhere(_.name == name)

  def startScan(timestamp: Instant): Scan =
    Scan(startedAt = Some(timestamp), scanner = Scanners.scanners)
}

object Scan {
  implicit val format: Format[Scan] = Json.format
}


/**
  * Events
  */
object ScanEvent {
  val Tag = AggregateEventTag[ScanEvent]
}
sealed trait ScanEvent extends AggregateEvent[ScanEvent] {
  override def aggregateTag: AggregateEventTag[ScanEvent] = ScanEvent.Tag
}

case class ScanFailed(timestamp: Instant, errorMsg: String) extends ScanEvent
object ScanFailed {
  implicit val format: Format[ScanFailed] = Json.format
}

case class ScanStarted(timestamp: Instant) extends ScanEvent
object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished(timestamp: Instant) extends ScanEvent
object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

case class ScannerStarted(name: String, timestamp: Instant) extends ScanEvent
object ScannerStarted {
  implicit val format: Format[ScannerStarted] = Json.format
}

case class ScannerFinsihed(name: String) extends ScanEvent
object ScannerFinsihed {
  implicit val format: Format[ScannerFinsihed] = Json.format
}

/**
  * Commands
  */
sealed trait ScanCommand

case class FailedScan(timestamp: Instant, errorMsg: String) extends ScanCommand with ReplyType[Done]

object FailedScan {
  implicit val format: Format[FailedScan] = Json.format
}


case class StartScan(timestamp: Instant) extends ScanCommand with ReplyType[Done]

object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class StartScanner(name: String, timestamp: Instant) extends ScanCommand with ReplyType[Done]
object StartScanner {
  implicit val format: Format[StartScanner] = Json.format
}

case class FinishScanner(name: String) extends ScanCommand with ReplyType[Done]
object FinishScanner {
  implicit val format: Format[FinishScanner] = Json.format
}

case object GetScan extends ScanCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

object ScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[StartScan],
    JsonSerializer[ScannerStatus],
    JsonSerializer[ScanStarted],
    JsonSerializer[GetScan.type],
    JsonSerializer[ScanFinished],
    JsonSerializer[StartScanner],
    JsonSerializer[FinishScanner],
    JsonSerializer[ScannerStarted],
    JsonSerializer[ScannerFinsihed]
  )
}

class ScanAlreadyRunningException(msg: String) extends Exception(msg)
class ScannerAlreadyFinishedException(msg: String) extends Exception(msg)
class ScannerAlreadyStartedException(msg: String) extends Exception(msg)
class ScannerNotFoundException(msg: String) extends Exception(msg)