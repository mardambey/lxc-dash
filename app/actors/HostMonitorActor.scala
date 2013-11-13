package actors

import play.api.libs.json._
import play.api.libs.json.Json._

import akka.actor.{Props, PoisonPill, Actor}

import play.api.libs.iteratee.{Concurrent, Enumerator}

import play.api.libs.iteratee.Concurrent.Channel
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import misc.LxcHost.stringToLxcHost

import misc.Conf
import play.libs.Akka
import actors.HostMonitorActor.HostInfo

object HostMonitorActor {

  type HostInfo = Map[String, Map[String, Seq[String]]]
  val actor = Akka.system.actorOf(Props(new HostMonitorActor(300)))
}

class HostMonitorActor(interval: Int) extends Actor {

  protected val cancellable = context.system.scheduler.schedule(0 second, interval second, self, Update)

  protected val log = Logger("application." + this.getClass.getName)

  protected var hostInfo : Option[HostInfo] = None

  override def receive = {

    case GetHostInfo => {
      log.debug("%s asking for data.".format(sender))
      sender ! hostInfo.getOrElse(Map[String, Map[String, Seq[String]]]())
    }
    case Update => {

      log.debug("Updating data...")
      implicit val sshUser = "root"

      val data = Conf.hosts.map(
        h => {
          val c = h.containers
          h -> Map("running" -> c.running.map(_.uri), "frozen" -> c.frozen.map(_.uri), "stopped" -> c.stopped.map(_.uri))
        }
      ).toMap

      hostInfo = Some(data)
    }
  }

  override def postStop() {
    cancellable.cancel()
  }
}

sealed trait HostMonitorMessage
case object Update extends HostMonitorMessage
case object GetHostInfo extends HostMonitorMessage
