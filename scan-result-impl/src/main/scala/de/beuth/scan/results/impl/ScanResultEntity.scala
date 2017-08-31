package de.beuth.scan.results.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.{InvalidCommandException, ReplyType}
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuh.databreach.api.DataBreachCombinedResults
import de.beuth.databreach.scanner.api.DataBreachResult
import de.beuth.scan.results.api.{CensysResult, ProfileResult, ScanResult}
import de.beuth.utils.ProfileLink
import play.api.libs.json.{Format, Json}
import de.beuth.utils.JsonFormats._

import scala.collection.immutable.Seq

/**
  * ScanRsultis the persiten write side entity for the ScanResultService
  *
  * Primary Identifier: keyword
  */
class ScanResultEntity extends PersistentEntity {

  type Command = ScanResultCommand
  type Event = ScanResultEvent
  type State = ScanResultState

  //init the state as emtpy
  override def initialState: ScanResultState = ScanResultState(Seq(), Seq(), Seq())

  //the behavior of the entity
  override def behavior: Behavior = Actions()
    .onCommand[AddCensys, Done] {
      case (AddCensys(c), ctx, state) =>
        ctx.thenPersist(CensysAdded(c)) {
          _ => ctx.reply(Done)
        }
    }.onCommand[AddLinkedin, Done] {
      case (AddLinkedin(l), ctx, state) =>
        ctx.thenPersist(LinkedinAdded(l)) {
          _ => ctx.reply(Done)
        }
    }.onCommand[AddXing, Done] {
      case (AddXing(x), ctx, state) =>
        ctx.thenPersist(XingAdded(x)) {
          _ => ctx.reply(Done)
        }
    }.onCommand[AddBreach, Done] {
    case (AddBreach(b), ctx, state) =>
      ctx.thenPersist(BreachAdded(b)) {
        _ => ctx.reply(Done)
      }
   }.onEvent{
      case (CensysAdded(c), state) => state.addCensys(c)
      case (LinkedinAdded(l), state) => state.addLinkedin(l)
      case (XingAdded(x), state) => state.addLinkedin(x)
      case (BreachAdded(b), state) => state.addBreach(b)
   }.orElse(getScanResult)

  private val getScanResult = Actions().onReadOnlyCommand[GetScanResult.type, ScanResult] {
    case (GetScanResult, ctx, state) => ctx.reply(ScanResult(entityId, state.censys, state.linkedin, state.xing))
  }
}

/**
  * The state of the entity
  * @param censys the censys results
  * @param linkedin the linkedin + databreach results
  * @param xing the xing + databreach results
  */
case class ScanResultState(censys: Seq[CensysResult], linkedin: Seq[ProfileResult], xing: Seq[ProfileResult]) {

  def addCensys(c: CensysResult) = copy(
    censys = addOrUpdate(censys, c, censys.indexWhere(_.ip == c.ip))
  )

  def addLinkedin(l: ProfileResult) = copy(
    linkedin = addOrUpdate(linkedin, l, linkedin.indexWhere(_.url == l.url))
  )

  def addXing(x: ProfileResult) = copy(
    xing = addOrUpdate(xing, x, xing.indexWhere(_.url == x.url))
  )

  //adss a breach pending on the derived Provider
  def addBreach(b: DataBreachCombinedResults) =
    ProfileLink.deriveProvider(b.url) match {
      case ProfileLink.PROVIDER_LINKED_IN => copy(linkedin = updateProfileWithBreach(linkedin, b))
      case ProfileLink.PROVIDER_XING => copy(xing = updateProfileWithBreach(xing, b))
    }

  /**
    * Helper method to add or update an entry in a list
    *
    * @param current the urrent list
    * @param toAdd the element to add
    * @param idx the index of the element in the list
    * @tparam T the type of the element
    */
  private def addOrUpdate[T](current: Seq[T], toAdd: T, idx: Int): Seq[T] =
      if(idx == -1)
         current :+ toAdd
      else
         current.updated(idx, toAdd)

  //updates a profile with breach informations
  private def updateProfileWithBreach(profileResults: Seq[ProfileResult], breach: DataBreachCombinedResults): Seq[ProfileResult] = {
    val idx = profileResults.indexWhere(_.url == breach.url)
    if(idx == -1) throw InvalidCommandException("Cant add BreachResults - Profile does not exist.")
    profileResults.updated(idx, profileResults(idx).copy(dataBreach = breach.results))
  }
}

object ScanResultState {
  implicit val format: Format[ScanResultState] = Json.format
}

/**
  * Commands
  */
trait ScanResultCommand {}

case object GetScanResult extends ScanResultCommand with ReplyType[ScanResult] {
  implicit val format: Format[GetScanResult.type] = singletonFormat(GetScanResult)
}

case class AddCensys(c: CensysResult) extends ScanResultCommand with ReplyType[Done]
object AddCensys {
  implicit val format: Format[AddCensys] = Json.format
}

case class AddLinkedin(l: ProfileResult) extends ScanResultCommand with ReplyType[Done]
object AddLinkedin {
  implicit val format: Format[AddLinkedin] = Json.format
}

case class AddXing(x: ProfileResult) extends ScanResultCommand with ReplyType[Done]
object AddXing {
  implicit val format: Format[AddXing] = Json.format
}

case class AddBreach(b: DataBreachCombinedResults) extends ScanResultCommand with ReplyType[Done]
object AddBreach {
  implicit val format: Format[AddBreach] = Json.format
}

/**
  * Events
  */
trait ScanResultEvent {}

case class CensysAdded(c: CensysResult) extends ScanResultEvent
object CensysAdded {
  implicit val format: Format[CensysAdded] = Json.format
}

case class LinkedinAdded(l: ProfileResult) extends ScanResultEvent
object LinkedinAdded {
  implicit val format: Format[LinkedinAdded] = Json.format
}

case class XingAdded(x: ProfileResult) extends ScanResultEvent
object XingAdded {
  implicit val format: Format[XingAdded] = Json.format
}

case class BreachAdded(b: DataBreachCombinedResults) extends ScanResultEvent
object BreachAdded {
  implicit val format: Format[BreachAdded] = Json.format
}


/**
  * The serializer registry
  */
object ScanResultSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[ScanResultState],
    JsonSerializer[AddXing],
    JsonSerializer[XingAdded],
    JsonSerializer[AddLinkedin],
    JsonSerializer[LinkedinAdded],
    JsonSerializer[AddCensys],
    JsonSerializer[CensysAdded],
    JsonSerializer[AddBreach],
    JsonSerializer[BreachAdded],
    JsonSerializer[CensysResult],
    JsonSerializer[ProfileResult],
    JsonSerializer[DataBreachCombinedResults],
    JsonSerializer[DataBreachResult],
    JsonSerializer[GetScanResult.type ]
  )
}