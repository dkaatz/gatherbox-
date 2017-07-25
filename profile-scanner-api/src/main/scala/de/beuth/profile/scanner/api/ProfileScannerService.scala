package de.beuth.profile.scanner.api



import akka.Done
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import de.beuth.scanner.commons.ScanStatusTopics
import play.api.libs.json.{Format, Json}


object ProfileScannerService {
  val NAME: String = "profile"
  val TOPIC_STATUS: String = s"${NAME}_status"
}

trait ProfileScannerService extends Service with ScanStatusTopics{

  /**
    * @todo docu
    * @return
    */
  def scanXingProfile(keyword: String): ServiceCall[ProfileUrl, Done]

  def scanLinkedinProfile(keyword: String): ServiceCall[ProfileUrl, Done]

  override def descriptor: Descriptor = {
    import Service._

    named("profile-scanner").withCalls(
      restCall(Method.POST, "/api/scanner/profile/xing/:keyword", scanXingProfile _),
      restCall(Method.POST, "/api/scanner/profile/linkedin/:keyword", scanLinkedinProfile _)
    ).withTopics(
      topic(ProfileScannerService.TOPIC_STATUS, statusTopic)
    ).withAutoAcl(true)
  }
}

case class ProfileUrl(url: String)
object ProfileUrl {
  implicit val format: Format[ProfileUrl] = Json.format
}