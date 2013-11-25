package actors

import akka.actor.{Cancellable, Props, Actor}

import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import misc.{Conf, LxcHost}

import play.libs.Akka
import actors.HostMonitorActor.HostInfo

object HostMonitorActor {

  //type HostInfo = Map[String, Map[String, Seq[String]]]
  case class HostInfo(name:String, load: String, containers: Map[String, Seq[String]])
  val actor = Akka.system.actorOf(Props(new HostMonitorActor(300)))
}

class HostMonitorActor(interval: Int) extends Actor {

  protected var cancellable: Option[Cancellable] = None

  protected val log = Logger(s"application.$this.getClass.getName")

  protected var hosts : Option[Seq[HostInfo]] = None

  protected var iter : Option[Iterator[String]] = None

  implicit val sshUser = "root"

  override def receive = {

    case GetHostInfo => {
      log.debug(s"$sender asking for data...")
      sender ! hosts.getOrElse(Seq.empty[HostInfo])
    }
    case Update => {

      if (iter.isDefined && iter.get.hasNext) {

        val host = iter.get.next()

        log.debug(s"Updating data for $host...")

        val h = new LxcHost(host)
        val c = h.containers
        val ctrs = Map[String, Seq[String]](
            "running" -> c.running.map(_.uri),
            "frozen"  -> c.frozen.map(_.uri),
            "stopped" -> c.stopped.map(_.uri))

        hosts = Some(hosts.get ++: Seq(HostInfo(host, h.load.get, ctrs)))

        if (!iter.get.hasNext) {
          // schedule an update
          cancellable = Some(context.system.scheduler.scheduleOnce(interval second, self, Update))
        } else {
          self ! Update
        }
      } else {
        iter = Some(Conf.hosts.iterator)
        hosts = Some(Seq.empty[HostInfo])
        self ! Update
      }
    }
  }

  override def postStop() {
    if (cancellable.isDefined) cancellable.get.cancel()
  }
}

sealed trait HostMonitorMessage
case object Update extends HostMonitorMessage
case object GetHostInfo extends HostMonitorMessage
