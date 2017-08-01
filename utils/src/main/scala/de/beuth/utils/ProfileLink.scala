package de.beuth.utils

import play.api.libs.json.{Format, Json}

/**
  * Case class to wrap an Link/URL with a dervied provider/type
  *
  * @param url The actual URL/Link
  * @param provider Descripes the source/provider type of the Link e.g. Xing/Linkedin
  */
case class ProfileLink(url: String, provider: String)
object ProfileLink {
  implicit val format: Format[ProfileLink] = Json.format

  /**
    * Types
    */
  val PROVIDER_LINKED_IN = "linkedin"
  val PROVIDER_XING = "xing"

  /**
    * Derives type of an url
    * @param url Url to dervie type from
    * @return
    */
  def deriveProvider(url: String): String = {
    // regex pattern for Linkedin URL's
    val LinkedInPattern = ".*(linkedin).*/in/.*".r
    // regex pattern for Xing URL's
    val XingPattern = ".*(xing).*/profile/.*".r

    //return correct type using pattern matching with the regex patterns defined above
    url match {
      case LinkedInPattern(m) => PROVIDER_LINKED_IN
      case XingPattern(m) => PROVIDER_XING
      case _ => throw UnkownProfileProviderException(s"Unable to derive Type from URL: $url")
    }
  }
}

case class UnkownProfileProviderException(message: String) extends Exception