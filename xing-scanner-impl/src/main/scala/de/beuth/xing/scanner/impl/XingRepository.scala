package de.beuth.xing.scanner.impl

import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}
import de.beuth.scanner.commons.{ProfileEventProcessor, ProfileRepository}

import scala.concurrent.ExecutionContext

private[impl] case class XingRepository(session: CassandraSession)(implicit ec: ExecutionContext) extends ProfileRepository("xing", session)
private[impl] case class XingEventProcessor(session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext) extends ProfileEventProcessor("xing", session, readSide)