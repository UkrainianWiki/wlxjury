package views

import db.scalikejdbc.{Rates, Round}
import play.api.i18n.Messages

/** A round's rating type as the round form and the rounds list show it, from message keys:
  * comments only, yes/no, or 1-N rating.
  */
object RatingType {

  def label(rates: Rates)(implicit messages: Messages): String =
    if (rates.id == Round.commentsOnlyRound.id) messages("rating.comments.only")
    else if (rates.id == Round.binaryRound.id) messages("rating.yes.no")
    else messages("1-x.rating", rates.id)
}
