package de.beuth.utils

import play.api.libs.json.{Format, Json}

case class ScrapingJob(keyword: String, payload: String)

object ScrapingJob {
  implicit val format: Format[ScrapingJob] = Json.format
}