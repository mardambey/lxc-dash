package actors

import akka.actor.{ActorRef, Cancellable, Props, Actor}

import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._

import misc.{CpuAcct, Memory, LxcHost}

import play.libs.Akka
import scala.collection.immutable.SortedMap
import scala.collection.immutable.Queue

object MonitorActor {

  val actor = Akka.system.actorOf(Props(new MonitorActor(10)))
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

  import FiniteQueue._

  protected var cancellable: Option[Cancellable] = None

  protected val log = Logger(s"application.$this.getClass.getName")

  protected var hostInfo: Option[HostInfo] = None

  protected val containerCpuMem = new scala.collection.mutable.HashMap[String, Queue[ContainerCpuMemory]]

  implicit val sshUser = "root"

  val MAX_CTR_CPU_MEM_LENGTH = 30

  override def receive = {

    case GetHostInfo => {
      log.debug(s"$sender asking for data...")
      sender ! hostInfo
    }

    case Update => {

      log.debug(s"Updating data for $host...")

      val h = new LxcHost(host)
      val c = h.containers

      val ctrs: Map[String, Seq[ContainerInfo]] = List(
        ("running", c.running),
        ("stopped", c.stopped),
        ("frozen", c.frozen))
        .map((t => {
        (t._1 -> t._2.map(ctr => {
          var buf: Queue[ContainerCpuMemory] = containerCpuMem.getOrElseUpdate(ctr.name, Queue[ContainerCpuMemory]())
          buf = buf.enqueueFinite(ContainerCpuMemory(ctr.cpuacct, ctr.memory, System.currentTimeMillis()/1000), MAX_CTR_CPU_MEM_LENGTH)

          containerCpuMem(ctr.name) = buf

          val arr = buf.toArray.filter(_ != null)

          ContainerInfo(
            ctr.name,
            ctr.ip,
            ctr.hostname,
            arr.map(info => Map("time" -> info.time, "value" -> info.cpuAcct.user)),
            arr.map(info => Map("time" -> info.time, "value" -> info.cpuAcct.system)),
            arr.map(info => Map("time" -> info.time, "value" -> info.memory.totalRss)),
            arr.map(info => Map("time" -> info.time, "value" -> info.memory.totalCache)),
            arr.map(info => Map("time" -> info.time, "value" -> info.memory.totalSwap)))
        }))
      })).toMap

      hostInfo = Some(HostInfo(host, h.load.get, ctrs, System.currentTimeMillis()/1000))
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
case object Update extends MonitorMessage
case object GetInfo extends MonitorMessage
case class AddHost(host: String) extends MonitorMessage

case class ContainerInfo(
  name: String,
  ip: String,
  hostname: String,
  cpuUser: Array[Map[String, Long]],
  cpuSystem: Array[Map[String, Long]],
  memRss: Array[Map[String, Long]],
  memCache: Array[Map[String, Long]],
  memSwap: Array[Map[String, Long]])

case class HostInfo(
  name:String,
  load: String,
  containers: Map[String, Seq[ContainerInfo]],
  lastUpdate: Long)

case class ContainerCpuMemory(
  cpuAcct: CpuAcct,
  memory: Memory,
  time: Long)

object FiniteQueue {
  implicit def queue2finitequeue[A](q: Queue[A]) : FiniteQueue[A] = new FiniteQueue[A](q)
  implicit def queue2finitequeue[A]() : FiniteQueue[A] = new FiniteQueue[A](Queue.empty)
}

class FiniteQueue[A](q: Queue[A]) {

  def enqueueFinite[B >: A](elem: B, maxSize: Int): Queue[B] = {
    var ret = q.enqueue(elem)
    while (ret.size > maxSize) { ret = ret.dequeue._2 }
    ret
  }
}

