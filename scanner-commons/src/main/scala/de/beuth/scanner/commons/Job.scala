package de.beuth.scanner.commons

import play.api.libs.json.{Format, Json}

/**
  * The job used  in Work Pulling pattern
  */
case class ScrapingJob(keyword: String, payload: String)

object ScrapingJob {
  implicit val format: Format[ScrapingJob] = Json.format
}