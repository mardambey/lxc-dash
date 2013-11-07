package actors

import play.api.libs.json._
import play.api.libs.json.Json._

import akka.actor.{PoisonPill, Actor}

import play.api.libs.iteratee.{Concurrent, Enumerator}

import play.api.libs.iteratee.Concurrent.Channel
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import misc.LxcHost.stringToLxcHost
import misc.Conf

class ClientActor(userId: Int, interval: Int = 30) extends Actor {

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

      val data = Conf.hosts.map(
        h => {
          val c = h.containers
          h -> Map("running" -> c.running.map(_.uri), "frozen" -> c.frozen.map(_.uri), "stopped" -> c.stopped.map(_.uri))
        }
      ).toMap

      val json = Map("data" -> toJson(data))
      log debug s"$self pushing to $userId"
      userChannel.channel.push(Json.toJson(json))
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

