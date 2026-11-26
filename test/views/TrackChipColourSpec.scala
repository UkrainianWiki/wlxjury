package views

import db.scalikejdbc.Round
import org.specs2.mutable.Specification
import services.RoundLineage
import services.RoundLineage.{Context, Kind, Track}

/** The Track chip's colour (views.TrackChip.colour, RoundLineage.colourSlots): each track the hash
  * of its key, as before, so the same track has the same colour in every contest and year; Main its
  * own; two tracks of one contest on the same token moved apart by name, never by round ids (the
  * hash gave "Quality" and "Quantity" the same one), without moving a track no other shares a token
  * with; with every token taken, the next least used token, so the colliding pair still differs.
  */
class TrackChipColourSpec extends Specification {

  private def context(year: Int) =
    Context(contestCategory = Some(s"Category:Images from Wiki Loves Monuments $year in Ukraine"))

  private def round(id: Long, name: String, previous: Seq[Long] = Nil, category: Option[String] = None,
      nomination: Option[String] = None): Round =
    Round(Some(id), id, Some(name), contestId = 1,
      previous = Option.when(previous.nonEmpty)(previous.mkString(",")),
      category = category, specialNomination = nomination)

  private def root(id: Long, scope: String, year: Int = 2026) =
    round(id, s"$scope-00", category = Some(s"Category:WLM $year in Ukraine - $scope"))

  /** The hash colour, as the chip had it before collisions were resolved. */
  private def hashed(t: Track) = s"wlx-track-${Math.floorMod(t.key.hashCode, TrackChip.Colours) + 1}"

  private def colours(l: RoundLineage.Lineage): Map[String, String] =
    l.tracks.map(t => t.name -> TrackChip.colour(t, l)).toMap

  private val quality = Track(Kind.Category, "Quality")
  private val quantity = Track(Kind.Category, "Quantity")

  "a track's chip colour" should {

    "be the hash colour for tracks that share no token (as before)" in {
      val l = RoundLineage(Seq("A", "B", "C", "D", "E", "F").zipWithIndex.map { case (s, i) => root(i + 1L, s) },
        context(2026))
      (l.tracks.size === 6) and forall(l.tracks)(t => TrackChip.colour(t, l) === hashed(t)) and
        (l.tracks.map(TrackChip.colour(_, l)).distinct.size === 6)
    }

    "keep Main its own" in {
      val l = RoundLineage(Seq(root(1, "Quality"), root(2, "Quantity"), round(3, "Q-02", Seq(1, 2))), context(2026))
      (l.tracks.head === Track.Main) and (TrackChip.colour(Track.Main, l) === "wlx-track-main") and
        (l.colours.contains(Track.Main) must beFalse)
    }

    "give Quality and Quantity two colours, Quality its hash, and the same two in another contest and year" in {
      // the two share a hash token
      val sameHash = hashed(quality) === hashed(quantity)
      val l2026 = RoundLineage(Seq(root(1, "Quality"), root(2, "Quantity"), round(3, "Q-02", Seq(1, 2))), context(2026))
      // another year: Quantity created first, other ids, no merge
      val l2025 = RoundLineage(Seq(root(10, "Quantity", 2025), root(20, "Quality", 2025), round(30, "Q-01", Seq(20))),
        context(2025))
      val c2026 = colours(l2026)
      sameHash and (c2026("Quality") === hashed(quality)) and (c2026("Quantity") !== c2026("Quality")) and
        (colours(l2025)("Quality") === c2026("Quality")) and (colours(l2025)("Quantity") === c2026("Quantity"))
    }

    "move a colliding track to the next free token, never a track that keeps its own" in {
      // Film's hash token is the one after Quality's, Харків's the one after that
      val l = RoundLineage(Seq(root(1, "Quality"), root(2, "Quantity"), root(3, "Film"),
        round(4, "Kh-00", nomination = Some("Харків"))), context(2026))
      val film = Track(Kind.Category, "Film")
      val kharkiv = Track(Kind.Nomination, "Харків")
      val c = colours(l)
      (c("Film") === hashed(film)) and (c("Харків") === hashed(kharkiv)) and (c("Quality") === hashed(quality)) and
        (c.values.toSet.size === 4)
    }

    "with every token taken, move a colliding track to the next least used one, never back onto its own" in {
      // G's hash token is A's, H's is B's; A … F take all six
      val names = Seq("A", "B", "C", "D", "E", "F", "G", "H")
      val l = RoundLineage(names.zipWithIndex.map { case (s, i) => root(i + 1L, s) }, context(2026))
      val c = l.tracks.map(t => t.name -> TrackChip.colour(t, l)).toMap
      (hashed(l.tracks(6)) === hashed(l.tracks(0))) and (hashed(l.tracks(7)) === hashed(l.tracks(1))) and
        forall(names.take(6))(n => c(n) === hashed(l.tracks(names.indexOf(n)))) and
        (c("G") === "wlx-track-3") and (c("H") === "wlx-track-4")
    }

    "keep WLM Ukraine 2025's Quality and Quantity apart among its 14 tracks" in {
      val nominations = Seq("Болгарська спадщина", "Війна руйнує пам'ятки", "Єврейська спадщина", "Німецька спадщина",
        "Пам'ятки дерев'яної архітектури України", "Польська спадщина", "Харків")
      val rounds = Seq("Exterior", "Film", "Interior", "Quality", "Quantity").zipWithIndex.map { case (s, i) => root(i + 1L, s, 2025) } ++
        Seq(round(6, "V-00", category = Some("Category:Images from Wiki Loves Monuments 2025 in Ukraine"))) ++
        nominations.zipWithIndex.map { case (n, i) => round(10L + i, s"N-$i", nomination = Some(n)) }
      val l = RoundLineage(rounds, context(2025))
      val c = colours(l)
      (l.tracks.size === 13) and (c("Quality") === hashed(quality)) and (c("Quantity") !== c("Quality"))
    }
  }

  "the colour slots" should {

    "depend only on the tracks, not on their order" in {
      val tracks = Seq(quality, quantity, Track(Kind.Category, "Film"), Track(Kind.Nomination, "Харків"))
      RoundLineage.colourSlots(tracks) === RoundLineage.colourSlots(tracks.reverse)
    }
  }
}
