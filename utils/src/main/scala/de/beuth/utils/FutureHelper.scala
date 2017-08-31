package de.beuth.utils

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ExecutionContext, Future}

/**
  * A class containing for future helpers
  *
  * @param ec
  */
case class FutureHelper()(implicit ec: ExecutionContext) {
  /**
    * Transforms a listfuture to a future list using "foldLeft" on  the collection of futures resolving
    * the next future and returning it or fallback to the current result if the future fails
    *
    * e.g.   List[Future[Int]] -- becomes --> Future[List[Int]]
    *        where the result only contains sucessfull resolved futures
    *
    * Solution by Idan Waisman found at: https://stackoverflow.com/questions/20874186/scala-listfuture-to-futurelist-disregarding-failed-futures
    *
    * @param in - SequenceLike of Future of Objects to resolve
    * @param cbf - implicit can build from, wich is a base trait for builder factories
    * @tparam A - ObjectLike Type
    * @tparam M - SequenceLike Type
    * @return Future of M of A
    */
   def allSuccessful[A, M[X] <: TraversableOnce[X]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] = {
    in.foldLeft(Future.successful(cbf(in))) {
      (current, left) ⇒ (for (r ← current; a ← left) yield r += a) fallbackTo current
    } map (_.result())
  }
}
