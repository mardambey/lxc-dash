package misc

import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import actors.{MonitorActor, AddHost, Update, HostMonitorActor}

object Global extends GlobalSettings {

  override def onStart(app: Application) {
    Conf.hosts.foreach(MonitorActor.actor ! AddHost(_))
  }

  override def onHandlerNotFound(request: RequestHeader) = {
    Redirect(controllers.routes.AppController.index())
  }

  override def onStop(app: Application) = {
  }

}
