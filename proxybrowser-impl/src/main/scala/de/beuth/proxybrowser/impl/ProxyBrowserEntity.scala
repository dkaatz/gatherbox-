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
    Seq[ProxyServer](),
    Seq[ProxyServer](),
    Seq[ProxyServer]()
  )

  override def behavior: Behavior = {
    Actions()
      .onCommand[Add, Done] {
        case (Add(servers), ctx, state) => {

          //filter servers that are already added somewhere (prevents double adding of servers)
          val filteredList = servers
            .filterNot(newProxy => state.free.exists(freeProxy => newProxy.host == freeProxy.host && newProxy.port == freeProxy.port))
            .filterNot(newProxy => state.reports.exists(freeProxy => newProxy.host == freeProxy.host && newProxy.port == freeProxy.port))
            .filterNot(newProxy => state.inUse.exists(freeProxy => newProxy.host == freeProxy.host && newProxy.port == freeProxy.port))

          //if there is no new server we do not persist an added event
          if(filteredList.isEmpty)
            ctx.done
          else
            ctx.thenPersist(Added(filteredList)) { _ => ctx.reply(Done) }
        }
      }.onCommand[GetNext, ProxyServer] {
        //there is no more free proxy server
        case (GetNext(), ctx, state: ProxyBrowserServerRepository) if state.free.isEmpty => {
          ctx.invalidCommand("No free proxy available")
          ctx.done

        }
        case (GetNext(), ctx, state) => {
          val next: ProxyServer = state.free.head
          ctx.thenPersist(
            InUseUpdated(next)
          ) {
            _ => ctx.reply(next)
          }
        }
    }.onCommand[UpdateFree, Done] {
      //the proxy server is already in the list of free servers
      case (UpdateFree(proxy), ctx, state) if state.free.exists(freeProxy => freeProxy.port == proxy.port && freeProxy.host == proxy.host) =>
        ctx.invalidCommand(s"Proxy: ${Json.toJson(proxy).toString()} is already free")
        ctx.done
      //the server was not in use
      case (UpdateFree(proxy), ctx, state) if !state.inUse.exists(inUseProxy => inUseProxy.port == proxy.port && inUseProxy.host == proxy.host) =>
        ctx.invalidCommand(s"Proxy: ${Json.toJson(proxy).toString()} is not in use, and cant be freed")
        ctx.done
      case (UpdateFree(proxy), ctx, state) =>
        ctx.thenPersist(FreeUpdated(proxy)) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateReported, Done] {
      case (UpdateReported(proxy), ctx, state) if !state.inUse.exists(inUseProxy => inUseProxy.port == proxy.port && inUseProxy.host == proxy.host) =>
        ctx.invalidCommand(s"Proxy: ${Json.toJson(proxy).toString()} can not be reported because he is not in use")
        ctx.done
      case (UpdateReported(proxy), ctx, state) =>
        ctx.thenPersist(ReportedUpdated(proxy)) {
          _ => ctx.reply(Done)
        }
    }.onEvent {
      case (InUseUpdated(proxy), state) => state.updateInUse(proxy)
      case (FreeUpdated(proxy), state) => state.updateFree(proxy)
      case (Added(servers), state) => state.add(servers)
      case (ReportedUpdated(proxy), state) => state.updateReported(proxy)
    }
    //readonly commands
    .onReadOnlyCommand[ListFree.type, Seq[ProxyServer]] {
      case (ListFree, ctx, state) => ctx.reply(state.free)
    }.onReadOnlyCommand[ListReported.type, Seq[ProxyServer]] {
      case (ListReported, ctx, state) => ctx.reply(state.reports)
    }.onReadOnlyCommand[ListInUse.type, Seq[ProxyServer]] {
      case (ListInUse, ctx, state) => ctx.reply(state.inUse)
    }
  }
}

case class ProxyBrowserServerRepository(free: Seq[ProxyServer], inUse: Seq[ProxyServer], reports: Seq[ProxyServer]) {
  private final val log: Logger = LoggerFactory.getLogger(classOf[ProxyBrowserServerRepository])
  def updateInUse(proxy: ProxyServer) = copy(free = free.filter(!_.host.equals(proxy.host)), inUse = inUse :+ proxy)

  def updateReported(proxy: ProxyServer) = copy(inUse = inUse.filter(!_.host.equals(proxy.host)) , reports = reports :+ proxy)

  def updateFree(proxy: ProxyServer) = copy(inUse = inUse.filter(!_.host.equals(proxy.host)), free = free :+ proxy)

  def add(servers: Seq[ProxyServer]) = copy(free = free ++ servers)
}

object ProxyBrowserServerRepository {
  implicit val format: Format[ProxyBrowserServerRepository] = Json.format[ProxyBrowserServerRepository]
}

sealed trait ProxyBrowserCommand

case class Add(servers: Seq[ProxyServer]) extends ProxyBrowserCommand with ReplyType[Done]
object Add {
  implicit val format: Format[Add] = Json.format
}

case class GetNext() extends ProxyBrowserCommand with ReplyType[ProxyServer]
object GetNext {
  implicit val format: Format[GetNext.type] = singletonFormat(GetNext)
}

case object ListFree extends ProxyBrowserCommand with ReplyType[Seq[ProxyServer]] {
  implicit val format: Format[ListFree.type] = singletonFormat(ListFree)
}

case object ListReported extends ProxyBrowserCommand with ReplyType[Seq[ProxyServer]] {
  implicit val format: Format[ListReported.type] = singletonFormat(ListReported)
}

case object ListInUse extends ProxyBrowserCommand with ReplyType[Seq[ProxyServer]] {
  implicit val format: Format[ListInUse.type] = singletonFormat(ListInUse)
}

case class UpdateFree(server: ProxyServer) extends ProxyBrowserCommand with ReplyType[Done]
object UpdateFree {
  implicit val format: Format[UpdateFree] = Json.format
}

case class UpdateReported(server: ProxyServer) extends ProxyBrowserCommand with ReplyType[Done]
object UpdateReported {
  implicit val format: Format[UpdateReported] = Json.format
}

/**
  * Events
  */
sealed trait ProxyBrowserEvent
case class Added(servers: Seq[ProxyServer]) extends ProxyBrowserEvent
object Added {
  implicit val format: Format[Added] = Json.format
}

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
    JsonSerializer[ListFree.type],
    JsonSerializer[ListInUse.type],
    JsonSerializer[ListReported.type],
    JsonSerializer[UpdateFree],
    JsonSerializer[UpdateReported],
    JsonSerializer[Add],
    JsonSerializer[Added],
    JsonSerializer[InUseUpdated],
    JsonSerializer[FreeUpdated],
    JsonSerializer[ReportedUpdated]
  )
}