package de.beuth.censys.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.censys.api.{CensysIpv4Result}
import de.beuth.scanner.commons._
import de.beuth.utils.JsonFormats.singletonFormat
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * Write-Side Entity of the CensysScanner
  *
  * Extending the default scanner behavior with an update event
  */
class CensysScannerEntity extends ScannerEntity {

  /**
    * We initalize the scanner with default values
    */
  override def initialState: Scan = Scan(None, Seq[CensysIpv4Result](), false)

  /**
    * Behavior of the entity
    */
  override def behavior: Behavior =
    //prepend the default scan status behavior
    scanStatusBehavior.orElse(
      Actions().onCommand[UpdateScan, Done] {
      //we just update without any conditions
      case (UpdateScan(ipv4), ctx, state: Scan) =>
        ctx.thenPersist(
          ScanUpdated(ipv4)
        ) {
          _ => ctx.reply(Done)
        }
      }.onEvent {
      case (ScanUpdated(ipv4), state: Scan) => state.update(ipv4)
      }).orElse(getScan)

  /**
    * Returns the current state
    */
  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] {  case (GetScan, ctx, state: Scan) => ctx.reply(state) }
}

/**
  * The state of the entity, containing the possible changes on the state
  *
  * @param startedat start time of scanner
  * @param ipv4  results of the scanner
  * @param finished indicator if scanner is finished
  */
case class Scan(startedat: Option[Instant], ipv4: Seq[CensysIpv4Result], finished: Boolean) extends ScannerState {

  //the scanner started
  def start(timestamp: Instant): Scan = copy(startedat=Some(timestamp), ipv4 = Seq[CensysIpv4Result](), finished = false)

  //the scanner received an update, so we join current results with the new results
  def update(ipv4: Seq[CensysIpv4Result]): Scan = copy(ipv4 = this.ipv4 ++ ipv4)

  //the scanner finished the scan
  def finish = copy(finished = true)
}
//companion object providing serialization an deserialization
object Scan {
  implicit val format: Format[Scan] = Json.format
}

/**
  * Command for updating the scanner with a list of results
  *
  * @param ipv4 list of results
  */
case class UpdateScan(ipv4: Seq[CensysIpv4Result]) extends ScannerCommand with ReplyType[Done]
//companion object providing serialization an deserialization
object UpdateScan {
  implicit val format: Format[UpdateScan] = Json.format
}
//case object providing serialization an deserialization
case object GetScan extends ScannerCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

/**
  * Update event
  *
  * @param ipv4 list of results
  */
case class ScanUpdated(ipv4: Seq[CensysIpv4Result]) extends ScannerUpdateEvent
//companion object providing serialization an deserialization
object ScanUpdated { implicit val format: Format[ScanUpdated] = Json.format }


/**
  * The serializer registry of the service
  */
object ScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[UpdateScan],
    JsonSerializer[ScanUpdated],
    JsonSerializer[GetScan.type]
  ) ++ ScannerSerialzierRegistry.serializers
}