package services

import db.scalikejdbc.{Round, User}
import org.specs2.mutable.Specification

/** [[RoundLineage.links]], pure: a round's previous rounds and the rounds created from it, from the
  * contest's rounds as read; and [[RoundLineage.Links.visibleTo]].
  */
class RoundLineageLinksSpec extends Specification {

  private def round(id: Long, previous: Seq[Long] = Nil, contestId: Long = 10L, cancelled: Boolean = false): Round =
    Round(id = Some(id), number = id, name = Some(s"R$id"), contestId = contestId,
      previous = Option.when(previous.nonEmpty)(previous.mkString(",")),
      status = Option.when(cancelled)(Round.Status.Cancelled))

  private def ids(rounds: Seq[Round]): Seq[Long] = rounds.map(_.getId)

  "RoundLineage.links" should {

    "list two previous rounds by id, and the rounds created from it" in {
      val (a, b) = (round(2), round(1))
      val r = round(5, previous = Seq(2, 1))
      val (n1, n2) = (round(7, previous = Seq(5)), round(6, previous = Seq(1, 5)))
      val links = RoundLineage.links(r, Seq(a, b, r, n1, n2, round(9, previous = Seq(1))))
      (ids(links.from) === Seq(1L, 2L)) and (ids(links.next) === Seq(6L, 7L)) and (links.isEmpty must beFalse)
    }

    "leave out a deleted previous round and one of another contest, not among the contest's rounds" in {
      val r = round(5, previous = Seq(1, 2, 3))
      // 1 deleted (not read), 3 in another contest (not among this contest's rounds)
      val links = RoundLineage.links(r, Seq(round(2), r))
      ids(links.from) === Seq(2L)
    }

    "leave out a cancelled previous round and a cancelled round created from it" in {
      val r = round(5, previous = Seq(1, 2))
      val links = RoundLineage.links(r, Seq(round(1, cancelled = true), round(2), r, round(6, previous = Seq(5), cancelled = true),
        round(7, previous = Seq(5))))
      (ids(links.from) === Seq(2L)) and (ids(links.next) === Seq(7L))
    }

    "keep a cancelled round's own previous rounds (its pages say where it came from)" in {
      val r = round(5, previous = Seq(1), cancelled = true)
      val links = RoundLineage.links(r, Seq(round(1), r))
      (ids(links.from) === Seq(1L)) and (links.next must beEmpty)
    }

    "be empty for a round with no edge, a round with no id, and never list the round itself" in {
      val self = round(5, previous = Seq(5))
      (RoundLineage.links(round(5), Seq(round(1), round(5))).isEmpty must beTrue) and
        (RoundLineage.links(round(5).copy(id = None), Seq(round(6, previous = Seq(5)))).isEmpty must beTrue) and
        (RoundLineage.links(self, Seq(self)).isEmpty must beTrue)
    }
  }

  "Links.visibleTo" should {

    "keep only the rounds the viewer may see the organisers' pages of" in {
      val r = round(5, previous = Seq(1, 2))
      // a previous round of another contest, were it among the rounds read
      val links = RoundLineage.links(r, Seq(round(1), round(2, contestId = 20L), r, round(6, previous = Seq(5), contestId = 20L)))
      val organizer = User("o", "o@example.com", Some(1L), Set("organizer"), contestId = Some(10L))
      val otherAdmin = User("a", "a@example.com", Some(2L), Set("admin"), contestId = Some(20L))
      val juror = User("j", "j@example.com", Some(3L), Set("jury"), contestId = Some(10L))
      (ids(links.visibleTo(organizer).from) === Seq(1L)) and (links.visibleTo(organizer).next must beEmpty) and
        (ids(links.visibleTo(otherAdmin).from) === Seq(2L)) and (ids(links.visibleTo(otherAdmin).next) === Seq(6L)) and
        (links.visibleTo(juror).isEmpty must beTrue)
    }
  }
}
