package actors

import akka.actor.{ActorRef, Cancellable, Props, Actor}

import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import misc.LxcHost

import play.libs.Akka
import scala.collection.immutable.SortedMap

object MonitorActor {

  val actor = Akka.system.actorOf(Props(new MonitorActor(300)))
}

/**
 * Keeps track of several HostMonitorActor instances asking them
 * to report host information back to it at a specified interval.
 *
 * This class can then hand the host information in one shot to
 * clients.
 *
 * @param interval sets how often the HostMonitorActor should
 *                 fetch information from it's host.
 */
class MonitorActor(interval: Int) extends Actor {

  protected val log = Logger(s"application.$this.getClass.getName")

  protected var hosts = Map.empty[String, ActorRef]

  protected var hostInfos = SortedMap.empty[String, HostInfo]

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

/**
 * Periodically fetches information about a specific host's containers.
 *
 * @param interval sets how often to fetch information from the host
 * @param host the host to fetch information from
 * @param listener optional, if passed, host information is reported back to it as HostInfo
 */
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
