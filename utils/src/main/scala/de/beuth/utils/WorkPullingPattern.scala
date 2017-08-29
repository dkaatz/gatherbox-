package de.beuth.utils
/**
  * Implementation of the akka Work Pulling Pattern to distribute tasks to workers without risking a mailbox overflow.
  *
  * This implementation is taken from: https://github.com/mpollmeier/akka-patterns/blob/master/src/main/scala/akkapatterns/WorkPullingPattern.scala
  *
  * @author Michael Pollmeier (additional documentation by David Kaatz)
  */
import akka.actor.Actor
import scala.collection.mutable
import akka.actor.ActorRef
import WorkPullingPattern._
import scala.reflect.ClassTag
import org.slf4j.{Logger, LoggerFactory}
import akka.actor.Terminated
import scala.concurrent.Future


/**
  * Singleton object containing specifications of Messages used by the Work Pulling Pattern implementation
  */
object WorkPullingPattern {
  sealed trait Message
  trait Epic[T] extends Iterable[T] //used by master to create work (in a streaming way)
  case object GimmeWork extends Message
  case object CurrentlyBusy extends Message
  case object WorkAvailable extends Message
  case class RegisterWorker(worker: ActorRef) extends Message
  case class Work[T](work: T) extends Message
}


/**
  * The master of wich takes care of collecting work and distributing it on pull requets to workers
  *
  * @tparam T Type of Work to solve
  */
class Master[T] extends Actor {
  private final val log: Logger = LoggerFactory.getLogger(classOf[Master[T]])
  val workers = mutable.Set.empty[ActorRef] // the registered workers
  var currentEpic: Option[Epic[T]] = None // the current list of work to do

  /**
    * Specifies what to do when receiving messages
    */
  def receive = {

    case epic: Epic[T] ⇒
      if (currentEpic.isDefined)   // we alread got work
        sender ! CurrentlyBusy
      else if (workers.isEmpty)    // we have no workers registered
        log.error("Got work but there are no workers registered.")
      else {
        currentEpic = Some(epic)
        workers foreach { _ ! WorkAvailable }
      }

    case RegisterWorker(worker) ⇒
      log.info(s"worker $worker registered")
      context.watch(worker)
      workers += worker

    case Terminated(worker) ⇒
      log.info(s"worker $worker died - taking off the set of workers")
      workers.remove(worker)

    case GimmeWork ⇒ currentEpic match {
      case None ⇒
        log.info("workers asked for work but we've no more work to do")
      case Some(epic) ⇒
        val iter = epic.iterator
        if (iter.hasNext)
          sender ! Work(iter.next)
        else {
          log.info(s"done with current epic $epic")
          currentEpic = None
        }
    }
  }
}

/**
  * The Worker that performs the acutal work for a given job "T"
  *
  * @param master the master where the worker is asking for work
  * @param manifest The manifest is a type hint so that the same serializer can be used for different classes.
  * @tparam T Type of work the worker is asking for
  */
abstract class Worker[T: ClassTag](val master: ActorRef)(implicit manifest: Manifest[T]) extends Actor {
  protected val log: Logger
  implicit val ec = context.dispatcher

  /**
    * Before starting and receiving any messages we register the worker and ask for work
    */
  override def preStart {
    master ! RegisterWorker(self)
    master ! GimmeWork
  }
  /**
    * Specifies what to do when receiving messages
    */
  def receive = {
    case WorkAvailable ⇒
      master ! GimmeWork
    case Work(work: T) ⇒
      // haven't found a nice way to get rid of that warning
      // looks like we can't suppress the erasure warning: http://stackoverflow.com/questions/3506370/is-there-an-equivalent-to-suppresswarnings-in-scala
      doWork(work) onComplete { case _ ⇒ master ! GimmeWork }
  }

  /**
    * The actual entrypoint for the work processing
    * @param work the work payload
    * @return returns a future of something
    */
  def doWork(work: T): Future[_]
}