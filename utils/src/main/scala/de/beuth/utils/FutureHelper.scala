package de.beuth.utils

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ExecutionContext, Future}

case class FutureHelper()(implicit ec: ExecutionContext) {
  /**
    * Fold left the collection of future's and wait for the previous future to finish and return it or fallback to the
    * current result if the future fails
    *
    * e.g.   List[Future[Int]] -- becomes --> Future[List[Int]]
    *        where the result only contains sucessfull resolved futures
    *
    * @param in - SequenceLike of Future of Objects to wait for
    * @param cbf - implicit can build from
    * @tparam A - ObjectLike Type
    * @tparam M - SequenceLike Type
    * @return
    */
   def allSuccessful[A, M[X] <: TraversableOnce[X]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] = {
    in.foldLeft(Future.successful(cbf(in))) {
      (current, left) ⇒ (for (r ← current; a ← left) yield r += a) fallbackTo current
    } map (_.result())
  }
}
