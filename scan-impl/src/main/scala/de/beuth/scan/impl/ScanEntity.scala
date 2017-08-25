package de.beuth.scan.impl

import java.time.{Instant, LocalDateTime}

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.scan.api.ScannerStatus
import de.beuth.scanner.commons._
import de.beuth.utils.JsonFormats._
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * ScanEntity is the persiten write side entity for the ScanService and represents the state of a scan by keyword
  *
  * Primary Identifier: keyword to scan
  */
class ScanEntity extends ScannerEntity {
  override def initialState: Scan = Scan(None , Seq[ScannerStatus](), true)

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
  override def behavior: Behavior = scanStatusBehavior.orElse(
    Actions().onCommand[StartScanner, Done] {

    //scanner does not exist
    case (StartScanner(name, timestamp), ctx, state: Scan) if state.scanner.filter(_.name == name).isEmpty => {
      ctx.invalidCommand(s"Scanner <$name> does not exist.")
      ctx.done
    }

    //scanner already started and still running
    case (StartScanner(name, timestamp), ctx, state: Scan)
      if !state.scanner.filter(_.name == name).head.startedAt.isEmpty && state.scanner.filter(_.name == name).filterNot(_.finished).isEmpty => {
      ctx.invalidCommand(s"Scanner <$name> can not start twice.")
      ctx.done
    }

    //valid startScanner command
    case (StartScanner(name, timestamp), ctx, state) =>
      ctx.thenPersist(ScannerStarted(name, timestamp)) { _ => ctx.reply(Done)}

  }.onCommand[FinishScanner, Done] {

    //scanner does not exist
    case (FinishScanner(name), ctx, state: Scan) if  state.scanner.filter(_.name == name).isEmpty =>
      ctx.invalidCommand(s"Scanner <$name> does not exist.")
      ctx.done

    //scanner already finsihed
    case (FinishScanner(name), ctx, state: Scan) if state.scanner.filter(_.name == name).filterNot(_.finished).isEmpty =>
      ctx.invalidCommand(s"Scanner <$name> can not finish twice.")
      ctx.done

    //last scanner finished
    case (FinishScanner(name), ctx, state: Scan) if  state.scanner.filter(_.finished).size == state.scanner.filter(_.name == name).filter(_.finished) == 1  =>
      ctx.thenPersistAll(
        ScannerFinsihed(name),
        ScanFinished(Instant.now())
      ) {
        case _ => ctx.reply(Done)
      }
    //intermediate scanner finished
    case (FinishScanner(name), ctx, state) =>
      ctx.thenPersist(ScannerFinsihed(name)) { _ => ctx.reply(Done)}
  }.onEvent {
    case (ScannerFinsihed(name), scan: Scan) => scan.finishScanner(name)
    case (ScannerStarted(name, timestamp), scan: Scan) => scan.startScanner(name, timestamp)
  }).orElse(getScan)


  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] {  case (GetScan, ctx, state: Scan) => ctx.reply(state) }
}

/**
  *
  * The current state held by the persistent entity.
  * It holds the last time the scanner started and the state of each scanner
  *
  * @param startedAt time the last scan got started
  * @param scanner list of scanners states
  */
case class Scan(startedAt: Option[Instant], scanner: Seq[ScannerStatus], finished: Boolean) extends ScannerState {

  def startScanner(name: String, timestamp: Instant): Scan =
    copy(scanner = scanner.updated(indexOfScanner(name), ScannerStatus(name, Some(timestamp), false)))


  def finishScanner(name:String): Scan =
    copy(scanner = scanner.updated(indexOfScanner(name), scanner(indexOfScanner(name)).copy(finished = true)))


  def indexOfScanner(name:String): Int = scanner.indexWhere(_.name == name)

  def start(timestamp: Instant): Scan =
    Scan(startedAt = Some(timestamp), scanner = Scanners.scanners, finished = false)

  def finish = copy(finished = true)
}

object Scan {
  implicit val format: Format[Scan] = Json.format
}

case class ScannerStarted(name: String, timestamp: Instant) extends ScannerUpdateEvent
object ScannerStarted {
  implicit val format: Format[ScannerStarted] = Json.format
}

case class ScannerFinsihed(name: String) extends ScannerUpdateEvent
object ScannerFinsihed {
  implicit val format: Format[ScannerFinsihed] = Json.format
}


case class StartScanner(name: String, timestamp: Instant) extends ScannerCommand with ReplyType[Done]
object StartScanner {
  implicit val format: Format[StartScanner] = Json.format
}

case class FinishScanner(name: String) extends ScannerCommand with ReplyType[Done]
object FinishScanner {
  implicit val format: Format[FinishScanner] = Json.format
}

case object GetScan extends ScannerCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

object ScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[ScannerStatus],
    JsonSerializer[GetScan.type],
    JsonSerializer[StartScanner],
    JsonSerializer[FinishScanner],
    JsonSerializer[ScannerStarted],
    JsonSerializer[ScannerFinsihed]
  ) ++ ScannerSerialzierRegistry.serializers
}