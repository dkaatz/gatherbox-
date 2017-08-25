package de.beuth.linkedin.scanner.impl

import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}
import de.beuth.scanner.commons.{ProfileEventProcessor, ProfileRepository}

import scala.concurrent.ExecutionContext

private[impl] case class LinkedinRepository(session: CassandraSession)(implicit ec: ExecutionContext) extends ProfileRepository("linkedin", session)
private[impl] case class LinkedinEventProcessor(session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext) extends ProfileEventProcessor("linkedin", session, readSide)