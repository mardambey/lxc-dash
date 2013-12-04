package misc

import scala.sys.process._
import java.net.InetAddress
import scala.collection.JavaConverters._
import play.api.{Logger, Play}

object LxcConf {

  protected val log = Logger("application." + this.getClass.getName)

  protected val LXC_USERNAME   = "application.lxc.username"
  protected val LXC_HOSTS      = "application.lxc.hosts"
  protected val LXC_INTERFACES = "application.lxc.container.interfaces"
  protected val LXC_CONFIG     = "application.lxc.container.config"
  protected val LXC_CPUACCT    = "application.lxc.container.cpuacct"

  val sshUser = Play.current.configuration.getString(LXC_USERNAME).get
  val hosts   = Play.current.configuration.getStringList(LXC_HOSTS).map(_.asScala.toList).get
  val interfaces = Play.current.configuration.getString(LXC_INTERFACES).get
  val config = Play.current.configuration.getString(LXC_CONFIG).get
  val cpuacct = Play.current.configuration.getString(LXC_CPUACCT).get
}

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

case class CpuAcct(user: Long, system: Long)

object Container {
  def apply(name: String, hostname: String, ip: String, config: Map[String, String], host: LxcHost) = new Container(name, hostname, ip, config, host)

}

class Container(val name: String, val hostname: String, val ip: String, val config: Map[String, String], host:LxcHost) extends Remote(ip) {

  def cpuacct : CpuAcct = {
    val path = LxcConf.cpuacct
    val values = host.ssh(s"cat $path/$name/cpuacct.stat")
      .getOrElse("")
      .split("\n")
      .map(_.trim)
      .map(_.split(" "))
      .filter(_.size == 2)
      .map(t => t(0).trim -> t(1).trim.toLong)
      .toMap

    CpuAcct(values.getOrElse("user", 0), values.getOrElse("system", 0))
  }
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
      val cmd : String = LxcConf.config.format(container)
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

  private val Alpha = """[a-zA-Z]""".r

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
      case Alpha(_) if ping(container) => Some(HostnameIp(container, resolveHost(container).getOrElse("")))
      case _ => detectHostnameIpFromIp(container)
    }
  }

  private def detectHostnameIpFromIp(container: String) : Option[HostnameIp] = {
    try {
      val i = LxcConf.interfaces.format(container)
      val ip : Option[HostnameIp] = ssh(s"cat $i")
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

        // try to figure out container's hostname and IP
        val hostIp = detectHostnameIp(container).getOrElse(HostnameIp("", ""))

        curBuf.map(_ += Container(container, hostIp.hostname, hostIp.ip, i, this)) }
    })
    
    LxcList(running, frozen, stopped)
  }
}

case class HostnameIp(hostname: String, ip: String)
