package gatling.setup

import db.scalikejdbc.Round

/** A top N the round form's save takes (the cut picker, regional winners plan §10.4): never one inside
  * a tie of the source round's ranking, which the save refuses. The simulations post a configured N
  * (100, 500) to the form; this moves it to the end of a tie it falls in.
  */
object TopNValid {

  /** The smallest valid top N of at least `n` from round `from` of the contest: `n` itself unless it is
    * inside a tie; all the candidates if `n` is more.
    */
  def atLeast(contestId: Long, from: Long, n: Int): Int = {
    val round = Round(None, 0, None, contestId, previous = Some(from.toString))
    services.TestRoundServices.roundService().cutPicker(round, None, None, None).map(_.result)
      .filter(_.m > 0)
      .fold(n)(r => (n to r.m).find(r.isValid).getOrElse(r.m))
  }
}
