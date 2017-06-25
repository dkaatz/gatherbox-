package de.beuth.proxybrowser.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.utils.JsonFormats.singletonFormat
import de.beuth.proxybrowser.api.ProxyServer
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

class ProxyBrowserEntity extends PersistentEntity {
  override type Command = ProxyBrowserCommand
  override type Event = ProxyBrowserEvent
  override type State = ProxyBrowserServerRepository

  override def initialState: ProxyBrowserServerRepository = ProxyBrowserServerRepository(
    Seq[ProxyServer](
      ProxyServer("82.165.72.56", 3128),
      ProxyServer("88.99.149.188", 31288),
      ProxyServer("144.76.32.78", 8080),
      ProxyServer("89.40.124.65", 3128),
      ProxyServer("5.189.189.196", 8080),
      ProxyServer("5.189.189.196", 8888),
      ProxyServer("89.40.116.171", 3128),
      ProxyServer("213.185.81.135", 80),
      ProxyServer("81.10.172.142", 3128),
      ProxyServer("46.29.251.41", 3128),
      ProxyServer("46.246.61.31", 3128),
      ProxyServer("46.29.251.11", 3128),
      ProxyServer("104.40.182.87", 3128),
      ProxyServer("137.74.254.198", 3128),
      ProxyServer("212.237.27.151", 3128),
      ProxyServer("212.237.16.96", 8080),
      ProxyServer("188.213.170.40", 8080),
      ProxyServer("212.237.27.151", 80),
      ProxyServer("212.237.38.216",8080),
      ProxyServer("159.255.9.171", 80),
      ProxyServer("46.151.145.4", 53281),
      ProxyServer("51.254.118.235", 3128)
    ),
    Seq[ProxyServer](),
    Seq[ProxyServer]()
  )

  override def behavior: Behavior = {
    case scan => Actions()
      .onCommand[GetNext, ProxyServer] {
        case (GetNext(), ctx, state: ProxyBrowserServerRepository) => {
          val next: ProxyServer = state.free.head
          ctx.thenPersist(
            InUseUpdated(next)
          ) {
            _ => ctx.reply(next)
          }
        }
    }.onCommand[UpdateFree, Done] {
      case (UpdateFree(proxy), ctx, state) =>
        ctx.thenPersist(FreeUpdated(proxy)) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateReported, Done] {
      case (UpdateReported(proxy), ctx, state) =>
        ctx.thenPersist(ReportedUpdated(proxy)) {
          _ => ctx.reply(Done)
        }
    }.onEvent {
      case (InUseUpdated(proxy), state) => state.updateInUse(proxy)
      case (FreeUpdated(proxy), state) => state.updateFree(proxy)
      case (ReportedUpdated(proxy), state) => state.updateReported(proxy)
    }
  }
}

case class ProxyBrowserServerRepository(free: Seq[ProxyServer], inUse: Seq[ProxyServer], reports: Seq[ProxyServer]) {
  private final val log: Logger = LoggerFactory.getLogger(classOf[ProxyBrowserServerRepository])
  def updateInUse(proxy: ProxyServer) = copy(free = free.filter(!_.host.equals(proxy.host)), inUse = inUse :+ proxy)

  def updateReported(proxy: ProxyServer) = copy(free = free.filter(!_.host.equals(proxy.host)), inUse = inUse.filter(!_.host.equals(proxy.host)) , reports = reports :+ proxy)

  def updateFree(proxy: ProxyServer) = copy(inUse = inUse.filter(!_.host.equals(proxy.host)), free = free :+ proxy)

}

object ProxyBrowserServerRepository {
  implicit val format: Format[ProxyBrowserServerRepository] = Json.format[ProxyBrowserServerRepository]
}

sealed trait ProxyBrowserCommand

case class GetNext() extends ProxyBrowserCommand with ReplyType[ProxyServer]
object GetNext {
  implicit val format: Format[GetNext.type] = singletonFormat(GetNext)
}

case class UpdateFree(server: ProxyServer) extends ProxyBrowserCommand with ReplyType[Done]
object UpdateFree {
  implicit val format: Format[UpdateFree] = Json.format
}

case class UpdateReported(server: ProxyServer) extends ProxyBrowserCommand with ReplyType[Done]
object UpdateReported {
  implicit val format: Format[UpdateReported] = Json.format
}

sealed trait ProxyBrowserEvent

case class InUseUpdated(proxy: ProxyServer) extends ProxyBrowserEvent
object InUseUpdated {
  implicit val format: Format[InUseUpdated] = Json.format
}

case class FreeUpdated(proxy: ProxyServer) extends ProxyBrowserEvent
object FreeUpdated {
  implicit val format: Format[FreeUpdated] = Json.format
}

case class ReportedUpdated(proxy: ProxyServer) extends ProxyBrowserEvent
object ReportedUpdated {
  implicit val format: Format[ReportedUpdated] = Json.format
}


object ProxyBorwserSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[ProxyBrowserServerRepository],
    JsonSerializer[ProxyServer],
    JsonSerializer[GetNext.type],
    JsonSerializer[UpdateFree],
    JsonSerializer[UpdateReported],
    JsonSerializer[InUseUpdated],
    JsonSerializer[FreeUpdated],
    JsonSerializer[ReportedUpdated]
  )
}