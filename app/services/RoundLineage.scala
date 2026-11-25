package services

import db.scalikejdbc.Round
import org.intracer.wmua.ContestJury
import services.regions.RegionScheme

import java.text.Collator
import java.util.Locale
import scala.collection.mutable

/** A contest's rounds as a graph (round lineage plan, docs/plans/2026-11-10-round-lineage.md): which
  * track each round is on, by the track rule v4 (§2.3, decision 3) and the manual label (decision 4),
  * the tracks in their order (Main, its feeders in the order they join, the other tracks), and each
  * track's spine. Slice A (§8.1) builds the tracks: the Track chip, the Track menu and the regional
  * winners' default final round read them, and the rounds map will later. There is no second rule.
  *
  * Pure: built from the contest's rounds (`Round.findByContest`, at most about a hundred) and the
  * contest, in memory. Edges are a round's `previous` rounds; leave-out rounds (V95) don't place a
  * round. Positions are by id, never by `created_at` (§1.4).
  */
object RoundLineage {

  /** The manual label that puts a round's track in the Main group (decision 4), as stored. */
  val MainLabel = "Main"

  /** What placed a track: its kind, in the URL's track key and for its order. */
  sealed abstract class Kind(val param: String)

  object Kind {
    /** Main: a merge of rounds of several tracks (rule 6), or the label "Main". */
    case object Main extends Kind("main")
    /** A manual label other than Main (decision 4). */
    case object Label extends Kind("label")
    /** A special nomination: the round's own (rule 1), by id prefix (rule 2), or inherited (rule 3). */
    case object Nomination extends Kind("nomination")
    /** A real subset of the contest's regions (rule 4): the region ids, sorted, comma-separated. */
    case object Region extends Kind("region")
    /** A root's category, less the contest's common prefix (rule 7). */
    case object Category extends Kind("category")
    /** A root's media type, without a category of its own (rule 7). */
    case object Media extends Kind("media")
    /** A root's name, with neither (rule 7). */
    case object Name extends Kind("name")

    val All: Seq[Kind] = Seq(Main, Label, Nomination, Region, Category, Media, Name)
  }

  /** A track: identical kind and name share it (roots of the same scope, identical labels). */
  final case class Track(kind: Kind, name: String) {

    /** Its stable key, the Track menu's `track=` value: `main`, or the kind and the name. */
    def key: String = if (kind == Kind.Main) Kind.Main.param else s"${kind.param}:$name"

    def isMain: Boolean = kind == Kind.Main
  }

  object Track {
    val Main: Track = Track(Kind.Main, MainLabel)
  }

  /** What the rule reads of the contest: its own category (left out of the common prefix, and no
    * category of a root's own), its nominations by id prefix (rule 2), its real regions (rule 4).
    */
  final case class Context(
      contestCategory: Option[String] = None,
      nominationsByPrefix: Map[String, String] = Map.empty,
      realRegions: Set[String] = Set.empty,
      // how the pages name a region track's regions
      scheme: RegionScheme = RegionScheme(None)
  )

  object Context {

    /** The contest's: its images category, its campaign's nominations of its year by id prefix
      * ([[RegionScheme.of]]), its country's top-level regions.
      */
    def of(contest: ContestJury): Context = {
      val scheme = RegionScheme.of(contest)
      Context(
        contest.images,
        scheme.byPrefix.map { case (prefix, n) => prefix -> n.name },
        scheme.country.map(_.regionById.keySet.toSet).getOrElse(Set.empty),
        scheme
      )
    }
  }

  /** The graph's tracks.
    *
    * @param rounds          the contest's rounds, by id
    * @param trackOf         each round's track
    * @param tracks          the tracks in order: Main, the tracks that feed it in the order they join
    *                        (the id of the first Main round that takes each), the other category,
    *                        label, media and name tracks by name, the nominations by name, the
    *                        regions by their first round
    * @param spines          each track's spine: the chain from its first round to its deepest live
    *                        round (the latest by id on ties), through cancelled rounds, which it
    *                        skips
    * @param merges          the rounds rule 6 placed in Main
    * @param missingPrevious a round's previous rounds that don't exist (a ghost, "missing round N"):
    *                        with none left the round is a root (rule 7), and the Rounds page warns
    * @param cycle           rounds on a cycle of previous rounds (none today: §1.1), placed as if
    *                        their unplaced previous rounds were missing
    */
  final case class Lineage(
      rounds: Map[Long, Round],
      trackOf: Map[Long, Track],
      tracks: Seq[Track],
      spines: Map[Track, Seq[Long]],
      merges: Set[Long],
      missingPrevious: Map[Long, Seq[Long]],
      cycle: Seq[Long],
      context: Context = Context()
  ) {

    def track(roundId: Long): Option[Track] = trackOf.get(roundId)

    def track(round: Round): Option[Track] = round.id.flatMap(trackOf.get)

    /** The track of that key ([[Track.key]]), if the contest has it. */
    def byKey(key: String): Option[Track] = tracks.find(_.key == key)

    /** Each round's track key (the Track menu's filter). */
    lazy val keys: Map[Long, String] = trackOf.view.mapValues(_.key).toMap

    /** The track's rounds, by id. */
    def roundsOf(track: Track): Seq[Round] =
      trackOf.collect { case (id, t) if t == track => rounds(id) }.toSeq.sortBy(_.getId)

    /** The track's deepest round: its spine's last (regional winners' default final round, #181
      * decision 2, of the Main track).
      */
    def deepest(track: Track): Option[Round] = spines.get(track).flatMap(_.lastOption).flatMap(rounds.get)

    /** Each track's chip colour ([[colourSlots]]), once for the contest: every chip and Track menu
      * swatch of a page reads this one assignment.
      */
    lazy val colours: Map[Track, Int] = colourSlots(tracks)

    /** The merge round alone on the Main track, if so (no other live round on it): its earlier
      * rounds are on their own tracks until an admin labels them "Main" (§8.1: the Rounds page's
      * hint).
      */
    def loneMerge: Option[Round] =
      trackOf.collect { case (id, t) if t.isMain && !cancelled(rounds(id)) => id }.toSeq match {
        case Seq(id) if merges.contains(id) => rounds.get(id)
        case _                              => None
      }
  }

  /** The Track chips' colours other than Main's (views.TrackChip: `--wlx-track-1` … `-6`). */
  val ColourSlots = 6

  /** A track's own colour slot, 0 until `slots`: a hash of its key, the same for the same track in
    * any contest and year.
    */
  def preferredSlot(track: Track, slots: Int = ColourSlots): Int = Math.floorMod(track.key.hashCode, slots)

  /** The contest's tracks' colour slots, none for Main (its own colour): each track its own slot
    * ([[preferredSlot]]) unless another track of the contest prefers it too. The tracks are taken
    * by name (case-insensitive, then exact, then key), never by round ids, so the same tracks get
    * the same slots every year:
    *  1. each slot goes to the first track by name that prefers it, so a track no other prefers
    *     keeps its own;
    *  2. the others, by name, take the next free slot after their own (+1, +2, … round to 0);
    *     with none free (more tracks than slots), the next least used one after their own, so
    *     the two tracks that wanted one slot still differ (WLM 2025's 14 tracks: Quality and
    *     Quantity), and the repeats are spread evenly.
    */
  def colourSlots(tracks: Seq[Track], slots: Int = ColourSlots): Map[Track, Int] = {
    val named = tracks.filterNot(_.isMain).distinct.sortBy(t => (t.name.toLowerCase(Locale.ROOT), t.name, t.key))
    val owners = named.groupBy(preferredSlot(_, slots)).view.mapValues(_.head).toMap
    val used = mutable.Map((0 until slots).map(s => s -> (if (owners.contains(s)) 1 else 0)): _*)
    named.map { t =>
      val own = preferredSlot(t, slots)
      t -> (if (owners(own) == t) own
            else {
              val slot = (1 until slots).map(i => (own + i) % slots).minBy(used)
              used(slot) += 1
              slot
            })
    }.toMap
  }

  def cancelled(round: Round): Boolean = round.status.contains(Round.Status.Cancelled)

  /** A round's edges as its own pages show them (round statistics, review, Edit round): the rounds
    * it was created from (its `previous` rounds) and the rounds created from it (those with it among
    * their `previous`), each by id, cancelled creations left out on both sides.
    */
  final case class Links(from: Seq[Round], next: Seq[Round]) {
    def isEmpty: Boolean = from.isEmpty && next.isEmpty

    /** Only the rounds `user` may see the organisers' pages of ([[db.scalikejdbc.User.canViewOrgInfo]]). */
    def visibleTo(user: db.scalikejdbc.User): Links = Links(from.filter(user.canViewOrgInfo), next.filter(user.canViewOrgInfo))
  }

  object Links {
    val empty: Links = Links(Nil, Nil)
  }

  /** The round's [[Links]], from its contest's rounds as already read (`Round.findByContest`): pure,
    * no query. A previous round not among them (another contest's, or deleted) is left out.
    */
  def links(round: Round, rounds: Seq[Round]): Links = round.id.fold(Links.empty) { id =>
    val live = rounds.filter(r => r.id.isDefined && !cancelled(r))
    val previous = round.previousIds.toSet
    Links(
      from = live.filter(r => previous.contains(r.getId) && r.getId != id).sortBy(_.getId),
      next = live.filter(r => r.getId != id && r.previousIds.contains(id)).sortBy(_.getId))
  }

  /** The contest's lineage. */
  def of(rounds: Seq[Round], contest: ContestJury): Lineage = apply(rounds, Context.of(contest))

  /** A category as compared and shown: without "Category:", underscores as spaces, trimmed. */
  private[services] def categoryName(category: String): String = {
    val title = category.trim.replace('_', ' ')
    (if (title.toLowerCase(Locale.ROOT).startsWith("category:")) title.substring("category:".length) else title).trim
  }

  /** The contest's common prefix (§2.3): the longest common prefix of the categories, cut back
    * so it ends at " - "; empty if they share none such.
    */
  private[services] def commonPrefix(categories: Seq[String]): String =
    if (categories.isEmpty) ""
    else {
      val lcp = categories.reduce { (a, b) =>
        a.zip(b).takeWhile { case (x, y) => x == y }.map(_._1).mkString
      }
      val cut = lcp.lastIndexOf(" - ")
      if (cut < 0) "" else lcp.substring(0, cut + 3)
    }

  /** A monument or region id's prefix, as a nomination's (`99-141-2306`: `99`), as the files'
    * regions are read ([[db.scalikejdbc.rewrite.ImageDbNew.regionOf]]).
    */
  private def prefixOf(id: String): String = id.trim.takeWhile(_ != '-').take(3)

  def apply(all: Seq[Round], context: Context): Lineage = {
    val rounds = all.filter(_.id.isDefined).sortBy(_.getId)
    val byId = rounds.map(r => r.getId -> r).toMap
    val previous: Map[Long, Seq[Long]] = rounds.map(r => r.getId -> r.previousIds.distinct.filter(byId.contains)).toMap
    val missing = rounds.flatMap { r =>
      val gone = r.previousIds.distinct.filterNot(byId.contains)
      Option.when(gone.nonEmpty)(r.getId -> gone)
    }.toMap

    // Kahn's order, the smallest id first among those ready; what is left is on a cycle
    val order = mutable.ArrayBuffer.empty[Long]
    val indegree = mutable.Map(rounds.map(r => r.getId -> previous(r.getId).size): _*)
    val later: Map[Long, Seq[Long]] =
      rounds.flatMap(r => previous(r.getId).map(_ -> r.getId)).groupMap(_._1)(_._2)
    val ready = mutable.PriorityQueue.empty[Long](Ordering[Long].reverse)
    indegree.collect { case (id, 0) => id }.foreach(ready.enqueue(_))
    while (ready.nonEmpty) {
      val id = ready.dequeue()
      order += id
      later.getOrElse(id, Nil).foreach { l =>
        indegree(l) -= 1
        if (indegree(l) == 0) ready.enqueue(l)
      }
    }
    val placed = order.toSet
    val cycle = rounds.map(_.getId).filterNot(placed.contains)
    val sequence = order.toSeq ++ cycle

    // a label groups by exact match after trimming (case and accents count, as round.track compares
    // them, utf8mb4_bin): "second look" and "Second look" are two tracks, each named as spelled;
    // only Main is matched in any case
    def label(r: Round): Option[Track] =
      r.track.map(_.trim.replaceAll("\\s+", " ")).filter(_.nonEmpty).map { l =>
        if (l.equalsIgnoreCase(MainLabel)) Track.Main else Track(Kind.Label, l)
      }

    // rules 1 and 2: the round's own nomination, or the one its regions or monuments all name by prefix
    def ownNomination(r: Round): Option[Track] =
      r.specialNomination.map(_.trim).filter(_.nonEmpty).map(Track(Kind.Nomination, _)).orElse {
        val ids = (r.regionIds ++ r.monumentIds).map(_.trim).filter(_.nonEmpty)
        val named = ids.map(id => context.nominationsByPrefix.get(prefixOf(id)))
        Option.when(ids.nonEmpty && named.forall(_.isDefined) && named.flatten.distinct.sizeIs == 1)(
          Track(Kind.Nomination, named.head.get))
      }

    // rule 4: a real subset of the regions (all the real regions are no region)
    def regionSubset(r: Round): Option[Track] = {
      val ids = r.regionIds.map(_.trim).filter(_.nonEmpty).distinct.sorted
      Option.when(ids.nonEmpty && !(context.realRegions.nonEmpty && context.realRegions.subsetOf(ids.toSet)))(
        Track(Kind.Region, ids.mkString(",")))
    }

    // rule 7's category: the round's own, not the contest's
    val contestCategory = context.contestCategory.map(categoryName).filter(_.nonEmpty)
    def rootCategory(r: Round): Option[String] =
      r.category.map(categoryName).filter(c => c.nonEmpty && !contestCategory.exists(_.equalsIgnoreCase(c)))

    // the roots that rule 7 places by category: their common prefix
    val roots = rounds.filter(r => previous(r.getId).isEmpty)
    val prefix = commonPrefix(
      roots.filter(r => label(r).isEmpty && ownNomination(r).isEmpty && regionSubset(r).isEmpty).flatMap(rootCategory))

    def rootScope(r: Round): Track =
      rootCategory(r).map { c =>
        val rest = if (prefix.nonEmpty && c.startsWith(prefix)) c.substring(prefix.length).trim else c
        Track(Kind.Category, if (rest.isEmpty) c else rest)
      }.orElse(r.mediaType.filter(m => m.nonEmpty && m != db.scalikejdbc.MediaType.All).map(Track(Kind.Media, _)))
        .getOrElse(Track(Kind.Name, r.description.trim))

    val trackOf = mutable.LinkedHashMap.empty[Long, Track]
    val merges = mutable.Set.empty[Long]
    sequence.foreach { id =>
      val r = byId(id)
      val from = previous(id).flatMap(trackOf.get).distinct
      val track = label(r)
        .orElse(ownNomination(r))
        .orElse(from match {
          case Seq(t) if t.kind == Kind.Nomination => Some(t) // rule 3
          case _                                    => None
        })
        .orElse(regionSubset(r))
        .orElse(from match {
          case Seq(t) => Some(t) // rule 5
          case Seq()  => None
          case _ => // rule 6
            merges += id
            Some(Track.Main)
        })
        .getOrElse(rootScope(r))
      trackOf(id) = track
    }

    // the order: Main, its feeders as they join, then the others
    val collator = Collator.getInstance(Locale.forLanguageTag("uk"))
    val byName: Ordering[Track] = (a, b) => collator.compare(a.name, b.name)
    val present = trackOf.values.toSeq.distinct
    val main = present.filter(_.isMain)
    val feeders = sequence.sorted.filter(id => trackOf(id).isMain).flatMap(id => previous(id).map(trackOf))
      .filterNot(_.isMain).distinct
    val others = present.filterNot(t => t.isMain || feeders.contains(t))
    val regions = others.filter(_.kind == Kind.Region)
      .sortBy(t => trackOf.collectFirst { case (id, `t`) => id }.getOrElse(Long.MaxValue))
    val nominations = others.filter(_.kind == Kind.Nomination).sorted(byName)
    val rest = others.filterNot(t => t.kind == Kind.Region || t.kind == Kind.Nomination).sorted(byName)
    val tracks = main ++ feeders ++ rest ++ nominations ++ regions

    // spines: each round's depth along its own track's previous rounds, cancelled ones included (a
    // cancelled round still links its track: M1 → M2 → M3 (cancelled) → M4 runs to M4); the deepest
    // live round ends the spine, and the cancelled rounds on the way are skipped (§2.3). Each round's
    // step back is its deepest such previous round, the latest by id on ties: fixed as the depths
    // are, so a cycle's rounds can't send the walk round it
    val depth = mutable.Map.empty[Long, Int]
    val back = mutable.Map.empty[Long, Long]
    val deeper: Ordering[Long] = Ordering.by((id: Long) => (depth(id), id))
    sequence.foreach { id =>
      val inTrack = previous(id).filter(p => trackOf(p) == trackOf(id) && depth.contains(p))
      inTrack.maxOption(deeper).foreach(back(id) = _)
      depth(id) = inTrack.map(depth).maxOption.fold(0)(_ + 1)
    }
    val spines = tracks.flatMap { t =>
      val live = depth.keys.filter(id => trackOf(id) == t && !cancelled(byId(id))).toSeq
      live.maxOption(deeper).map { last =>
        t -> Iterator.iterate(Option(last))(_.flatMap(back.get)).takeWhile(_.isDefined).flatten.toSeq.reverse
          .filterNot(id => cancelled(byId(id)))
      }
    }.toMap

    Lineage(byId, trackOf.toMap, tracks, spines, merges.toSet, missing, cycle, context)
  }

}
