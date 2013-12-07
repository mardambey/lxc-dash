package actors

import play.api.libs.json._

import akka.actor.{PoisonPill, Actor}

import play.api.libs.iteratee.{Concurrent, Enumerator}

import play.api.libs.iteratee.Concurrent.Channel
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.Await

class ClientActor(userId: Int, interval: Int = 250) extends Actor {

  protected val cancellable = context.system.scheduler.schedule(0 second, interval second, self, UpdateClient)

  protected case class UserChannel(userId: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  protected val log = Logger("application." + this.getClass.getName)

  log.debug(s"starting new socket for user $userId")

  val userChannel: UserChannel = {
    val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
    UserChannel(userId, broadcast._1, broadcast._2)
  }

  override def receive = {

    case StartSocket => sender ! userChannel.enumerator
    case UpdateClient => {

      implicit val sshUser = "root"
      implicit val timeout = Timeout(3 seconds)

      val f = ask(MonitorActor.actor, GetInfo).mapTo[Seq[HostInfo]]
      val data = Await.result(f, timeout.duration)

      val nonEmptyData = data.map(hostInfo => {

        val states = hostInfo.containers

        val goodStates = states
          // only return non-empty states
          .filter(state => {
            !state._2.isEmpty
          })

        Json.obj(
          "host" -> hostInfo.name,
          "load" -> hostInfo.load,
          "lastUpdate" -> hostInfo.lastUpdate,
          "containers" -> goodStates
        )
      })

      log debug s"$self pushing to $userId"
      userChannel.channel.push(Json.toJson(nonEmptyData))
    }

    case SocketClosed => {

      log debug s"closed socket for $userId"
      cancellable.cancel()
      self ! PoisonPill
    }

  }

}

sealed trait SocketMessage
case object StartSocket extends SocketMessage
case object SocketClosed extends SocketMessage
case object UpdateClient extends SocketMessage
case object Stop extends SocketMessage

