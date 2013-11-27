package misc

import java.net.InetAddress

object Conf {
  val sshUser = "root"

  val hosts = List(
    "blackhowler.gene",
    "turkish")
//    "localhost")
//    "hydrogen",
//    "helium",
//    "moorland",
//    "shrubland",
//    "grassland",
//    "woodland",
//    "lxchost-01",
//    "lxchost-02",
//    "brook",
//    "canton",
//    "crater",
//    "creek",
//    "desert",
//    "field",
//    "forest",
//    "grove",
//    "hamlet",
//    "lake",
//    "mountain",
//    "prairie",
//    "river",
//    "steppe",
//    "stream",
//    "taiga",
//    "tarn",
//    "tundra",
//    "valley",
//    "volcano")

  def currentKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCrxsjHqytdf4xDSPUWv/oSdieTLfFvQrdYBbCgdLkVL6Q5cXCoWl66BkTr5Rkf+wEfutaCru8sB2eUZKFWvp99+0IU20h5dAz4Q3yPqyUq8KQBXOvh32DmoUY8DVH+MxzoZ+y/RDmMUgVvtqaMlrFR0rOs9yRiStqDidKQYd/LaiW1H7sapbLyrwyiiCQ/7qWlHuoBVLatMZpHfomGP4BU9OwxK4/xY0AE3Fjz1zcP9Z8zPVizmQjpfREkGGM5I/wEpc5iozUpygewQw3ePNxXx7n3DLtdy1LJEaIPDqhHe+NkHNQfj1+Is9aEmp1i4rpSCcRlsbE1ZbV1HslV5RVl hisham@turkish"
}

import scala.sys.process._

object SSH {

  def run(host: String, cmd: String)(implicit sshUser: String = "") : Option[String] = {
    try { 
      val user = if (sshUser != null && !sshUser.isEmpty) sshUser + "@" else ""
      Some("ssh -t %s%s -- %s".format(user, host, cmd).!!)
    }
    catch {
      case t:Throwable => {
        println("Issue with %s: %s: %s".format(host, cmd, t.getStackTraceString))
        None
      }
    }
  }

  def copy(host: String, localPath: String, remotePath: String)(implicit sshUser: String = "") : Option[Int] = {
    try { 
      val user = if (sshUser != null && !sshUser.isEmpty) sshUser + "@" else ""
      Some("scp -r %s%s %s %s".format(user, host, localPath, remotePath).!)
    }
    catch {
      case t:Throwable => {
        println("Issue with scp to %s: %s -> %s, %s".format(host, localPath, remotePath, t.getStackTraceString))
        None
      }
    }
  }

}

class Remote(uri: String)(implicit sshUser: String = "") {
  def ssh(cmd: String) : Option[String] = {
    SSH.run(uri, cmd)
  }

  def scp(localPath: String, remotePath: String) : Option[Int] = {
    SSH.copy(uri, localPath, remotePath)
  }

  def load : Option[String] = {
    ssh("uptime").map(_.trim)
  }
}

object Container {
  def apply(name: String, hostname: String, ip: String, config: Map[String, String]) = new Container(name, hostname, ip, config)
}

class Container(val name: String, val hostname: String, val ip: String, val config: Map[String, String]) extends Remote(ip) {
}

case class LxcList(
  running : Seq[Container], 
  frozen  : Seq[Container], 
  stopped : Seq[Container]) {

  override def toString() : String = {
    "{running : [%s]}, {frozen: [%s]}, {stopped: [%s]}".format(running.mkString(","), frozen.mkString(","), stopped.mkString(","))
  }
}

object LxcHost {

  val RUNNING = "RUNNING"
  val FROZEN  = "FROZEN"
  val STOPPED = "STOPPED"

  def apply(uri: String)(implicit sshUser: String = "") = new LxcHost(uri)(sshUser)

  implicit def stringToLxcHost(uri: String)(implicit sshUser: String = "") : LxcHost = new LxcHost(uri)(sshUser)
}

class LxcHost(val uri: String)(implicit sshUser: String = "") extends Remote(uri) {

  import LxcHost._

  def info(container: String) : Map[String, String] = {

    try {
      val cmd : String = "cat /var/lib/lxc/%s/config".format(container)
      ssh(cmd)
        .getOrElse("")
        .split("\n")
        .map(_.trim)
        .filter(!_.isEmpty)
        .filter(!_.startsWith("#"))
        .filter(_.contains("="))
        .map(_.split("="))
        .filter(_.size == 2)
        .map(t => t(0).trim -> t(1).trim)
        .toMap
    } catch {
      case e: Exception => {
        Map.empty[String, String]
      }
    }

  }

  private def ping(host: String) : Boolean = {
    try { "ping -c1 %s".format(host).!!.contains("bytes from") }
    catch { case t:Throwable => false }
  }

  private val alpha = """[a-zA-Z]""".r

  private def resolveHost(host: String) : Option[String] = try { Some(InetAddress.getByName(host).getHostAddress) } catch {
    case t:Throwable => None
  }

  private def reverseLookupIp(ip: String) : Option[String] = try {
    val h = InetAddress.getByName(ip).getHostName

    if (h.equals(ip)) None
    else Some(h)

  } catch {
    case t:Throwable => None
  }

  private def detectHostnameIp(container: String) : Option[HostnameIp] = {

    container match {
      case alpha(_) if (ping(container)) => Some(HostnameIp(container, resolveHost(container).getOrElse("")))
      case _ => detectHostnameIpFromIp(container)
    }
  }

  private def detectHostnameIpFromIp(container: String) : Option[HostnameIp] = {
    try {
      val ip : Option[HostnameIp] = ssh("cat /var/lib/lxc/%s/rootfs/etc/network/interfaces".format(container))
        .getOrElse("")
        .split("\n")
        .map(_.trim)
        .filter(!_.isEmpty)
        .filter(!_.startsWith("#"))
        .filter(_.startsWith("address"))
        .map(_.split(" "))
        .filter(_.size == 2)
        .map(_(1).trim)
        .dropWhile(!ping(_))
        .headOption
        .map(i => HostnameIp(reverseLookupIp(i).getOrElse(""), i))

      ip
    } catch {
      case t:Throwable => None
    }
  }

  def containers: LxcList = {
    val running = new scala.collection.mutable.ArrayBuffer[Container]()
    val frozen  = new scala.collection.mutable.ArrayBuffer[Container]()
    val stopped = new scala.collection.mutable.ArrayBuffer[Container]()
    var curBuf : Option[scala.collection.mutable.ArrayBuffer[Container]] = None

    ssh("lxc-list")
    .getOrElse("")
    .split("\n")
    .filter(!_.isEmpty)
    .map(_.replace("(auto)", ""))
    .map(_.trim)
    .foreach({
      case RUNNING   => { curBuf = Some(running) }
      case FROZEN    => { curBuf = Some(frozen)  }
      case STOPPED   => { curBuf = Some(stopped) }
      case container => {
        val i = info(container)

        // try to figure out container's hostname
        val hostIp = detectHostnameIp(container).getOrElse(HostnameIp("", ""))

        curBuf.map(_ += Container(container, hostIp.hostname, hostIp.ip, i)) }
    })
    
    LxcList(running, frozen, stopped)
  }
}

//// implicits
//import LxcHost._
//
//// test code below
//
//implicit val sshUser = "root"
//
//Conf.hosts.foreach(
//  host => {
//    println (host)
//    host.containers.running.head.config.foreach(print)
//  }
//)

case class HostnameIp(hostname: String, ip: String)
