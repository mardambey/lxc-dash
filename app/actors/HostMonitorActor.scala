package actors

import akka.actor.{ActorRef, Cancellable, Props, Actor}

import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import misc.LxcHost

import play.libs.Akka

object MonitorActor {

  val actor = Akka.system.actorOf(Props(new MonitorActor(300)))
}

class MonitorActor(interval: Int) extends Actor {

  protected val log = Logger(s"application.$this.getClass.getName")

  protected var hosts = Map.empty[String, ActorRef]

  protected var hostInfos = Map.empty[String, HostInfo]

  override def receive = {

    case AddHost(host) => {
      val actor = HostMonitorActor(interval, host, Some(self))
      actor ! Update
      hosts = hosts + (host -> actor)
    }

    case GetInfo => {
      sender ! hostInfos.values.toSeq
    }

    case hostInfo:HostInfo => {
      hostInfos = hostInfos + (hostInfo.name -> hostInfo)
    }
  }
}

object HostMonitorActor {

  def apply(interval: Int, host: String, listener: Option[ActorRef] = None) = Akka.system.actorOf(Props(new HostMonitorActor(interval, host, listener)))
}

class HostMonitorActor(interval: Int, host: String, listener: Option[ActorRef] = None) extends Actor {

  protected var cancellable: Option[Cancellable] = None

  protected val log = Logger(s"application.$this.getClass.getName")

  protected var hostInfo: Option[HostInfo] = None

  implicit val sshUser = "root"

  override def receive = {

    case GetHostInfo => {
      log.debug(s"$sender asking for data...")
      sender ! hostInfo
    }

    case Update => {

      log.debug(s"Updating data for $host...")

      val h = new LxcHost(host)
      val c = h.containers
      val ctrs = Map[String, Seq[String]](
        "running" -> c.running.map(_.uri),
        "frozen"  -> c.frozen.map(_.uri),
        "stopped" -> c.stopped.map(_.uri))

      hostInfo = Some(HostInfo(host, h.load.get, ctrs))
      if (listener.isDefined) listener.get ! hostInfo.get
      listener.map(_ ! hostInfo.get)

      // schedule an update
      cancellable = Some(context.system.scheduler.scheduleOnce(interval second, self, Update))
    }
  }

  override def postStop() {
    if (cancellable.isDefined) cancellable.get.cancel()
  }
}

sealed trait HostMonitorMessage
case object GetHostInfo extends HostMonitorMessage

sealed trait MonitorMessage
case class AddHost(host: String) extends MonitorMessage
case object Update extends MonitorMessage
case object GetInfo extends MonitorMessage

case class HostInfo(name:String, load: String, containers: Map[String, Seq[String]])
