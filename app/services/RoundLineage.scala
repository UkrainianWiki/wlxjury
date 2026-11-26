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
      scheme: RegionScheme = RegionScheme(None),
      // the contest's own words (its name, year, country, its images category's main part), lower
      // case: what a root category's name drops at its ends when the roots share no start or end
      // ([[scopeNames]], [[Context.boilerplateOf]])
      boilerplate: Set[String] = Set.empty
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
        scheme,
        boilerplateOf(contest)
      )
    }

    /** The contest's words, lower case: those of its name, year and country, and of its images
      * category up to its first " - " or "/" (the campaign's own name, as "Images from Wiki Science
      * Competition 2025 in Bangladesh" where the contest is "Wiki Loves Science"). Not the rest of
      * that category: it may be a subcategory ("… in Serbia - protected species (final)") whose
      * words name other rounds' tracks ("protected species", "final round").
      */
    def boilerplateOf(contest: ContestJury): Set[String] = {
      val images = contest.images.map(categoryName).map { c =>
        val ts = tokens(c)
        ts.takeWhile(t => !Dashes.contains(t.text) && t.text != "/")
      }.getOrElse(Nil)
      (Seq(contest.name, contest.year.toString, contest.country).flatMap(tokens) ++ images)
        .map(_.word).filter(_.nonEmpty).toSet
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

    /** Whether none of the round's previous rounds exists ([[missingPrevious]]): it is then placed as a
      * round with no previous round (rule 7). With some left, its track comes as theirs would.
      */
    def allPreviousMissing(roundId: Long): Boolean =
      missingPrevious.get(roundId).exists(gone => rounds.get(roundId).exists(_.previousIds.forall(gone.contains)))

    /** The tracks of these rounds (a page's, e.g. the rounds the viewer sees), in [[tracks]]'s order. */
    def tracksOf(listed: Seq[Round]): Seq[Track] = {
      val of = listed.flatMap(track).toSet
      tracks.filter(of.contains)
    }

    /** Each track's number of rounds. */
    lazy val sizes: Map[Track, Int] = trackOf.values.groupBy(identity).view.mapValues(_.size).toMap

    /** A track named after its round (rule 7's last case: a first round with neither a category
      * nor a media type that tells it apart) with no round created from another on it: one round,
      * or unlinked rounds of one name. A chip of it would only repeat the round's name (26.11.17.2).
      */
    def selfNamedTrack(track: Track): Boolean = selfNamedTracks.contains(track)

    // the name tracks whose rounds are all first rounds (no previous round in the contest): one
    // round, or several unlinked rounds of one name (WLA Ghana 2026's two "ROUND 2")
    private lazy val selfNamedTracks: Set[Track] = trackOf.toSeq.groupMap(_._2)(_._1).collect {
      case (t, ids) if t.kind == Kind.Name && ids.forall(id => rounds(id).previousIds.forall(p => !rounds.contains(p))) => t
    }.toSet

    /** The round is alone on a track named after it ([[selfNamedTrack]]). */
    def selfNamed(round: Round): Boolean = this.track(round).exists(selfNamedTrack)

    /** Whether the pages show tracks at all: with two tracks or more among `listed` (all the
      * contest's rounds by default) that say more than a round's own name (not [[selfNamedTrack]]). One
      * such track says nothing, so a contest with fewer, as one whose rounds are unlinked and each
      * named after itself, has neither the Track chips nor the Track menu
      * (RoundsFilterBar.trackMenuShown reads this too).
      */
    def showsTracks(listed: Seq[Round] = rounds.values.toSeq): Boolean =
      tracksOf(listed).filterNot(selfNamedTrack).sizeIs > 1

    /** Whether the contest's rounds show their Track chips ([[showsTracks]] of all its rounds). */
    lazy val chipsShown: Boolean = showsTracks()

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

  /** Other pages' namespaces: a round's source "Category:File:…" or "Category:Commons:…" (a file list
    * or a page pasted into the category field) is no category.
    */
  private val Namespaces = Set("file", "image", "media", "commons", "user", "user talk", "template", "special",
    "project", "help", "portal", "wikipedia", "category", "talk", "module", "mediawiki")

  /** A round's category as a track may be named after it ([[categoryName]]): only a "Category:" page
    * that names no other page; None for anything else (no track name from a file list or a
    * Commons: page: the root is named by its media type or its name).
    */
  private[services] def categoryOf(value: String): Option[String] = {
    val title = value.trim.replace('_', ' ')
    Option.when(title.toLowerCase(Locale.ROOT).startsWith("category:"))(categoryName(title)).filter { c =>
      val colon = c.indexOf(':')
      c.nonEmpty && (colon < 0 || !Namespaces.contains(c.substring(0, colon).trim.toLowerCase(Locale.ROOT)))
    }
  }

  /** Words that name no track at a category name's ends, whatever the contest. */
  private[services] val CommonWords: Set[String] = Set("images", "image", "from", "in", "the", "of", "for", "and", "commons")

  /** A word of a category name: its text where it stands, and its letters and digits in lower case
    * ([[word]], empty for a separator: "-", "–", ":", "/").
    */
  private[services] final case class Token(text: String, start: Int, end: Int) {
    val word: String = text.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").toLowerCase(Locale.ROOT)
  }

  private val Dashes = Set("-", "–", "—")

  private val TokenPattern = "[^\\s/:]+|[/:]".r

  private[services] def tokens(name: String): Seq[Token] =
    TokenPattern.findAllMatchIn(name).map(m => Token(m.matched, m.start, m.end)).toSeq

  /** The root categories' track names (rule 7, §2.3), each category (as [[categoryName]] gives it)
    * to the part that tells it apart, deterministic for the same categories:
    *  1. with two categories or more, the words they all start with, and those they all end with,
    *     dropped (each keeping a word): "Wiki Loves Monuments in Ukraine 2025 - Quality" and
    *     "… - Quantity": "Quality" and "Quantity"; a single category: up to its last " - ";
    *  2. if that dropped nothing (the roots share no start or end, as in Wiki Loves Earth Ukraine),
    *     the contest's own words (`boilerplate`: its name, year, country and images category's main
    *     part, in any case), years, [[CommonWords]] and separators dropped at each end, where they make a run
    *     of three words or more or end at a separator: "Wiki Loves Earth Ukraine 2026 -
    *     Quality": "Quality", "Images from Wiki Loves Earth 2026 Nature Loss Special Category":
    *     "Nature Loss Special Category";
    *  3. two categories on one name keep step 1's names, and if still one, their full names.
    * Categories that differ only in case are one, named after the first in sorted order. Empty
    * when nothing but such words is left: the root is named by its media type or its name instead.
    */
  private[services] def scopeNames(categories: Seq[String], boilerplate: Set[String]): Map[String, String] = {
    // categories that differ only in case are one ("wiki loves earth 2026 …" and "Wiki Loves Earth 2026 …"),
    // named after the first of them in sorted order
    val groups = categories.distinct.groupBy(_.toLowerCase(Locale.ROOT)).values.map(_.sorted).toSeq
    val names = groups.map(_.head).sorted
    val toks = names.map(c => c -> tokens(c)).toMap
    def common(seqs: Seq[Seq[String]]): Int =
      if (seqs.isEmpty) 0 else (0 until seqs.map(_.size).min).takeWhile(i => seqs.forall(_(i) == seqs.head(i))).size
    def kept(c: String, pre: Int, suf: Int): Seq[Token] = toks(c).slice(pre, toks(c).size - suf)
    // a word that names nothing on its own: a separator, a year, the contest's words, the common ones
    def noise(t: Token) = t.word.isEmpty || t.word.matches("\\d{4}") || boilerplate.contains(t.word) ||
      CommonWords.contains(t.word)
    def keepsWord(pre: Int, suf: Int) = names.forall(c => kept(c, pre, suf).exists(!noise(_)))
    val (pre, suf) =
      if (names.sizeIs < 2) {
        // one category: up to its last " - ", as §2.3's prefix of a single category
        val dash = names.headOption.fold(-1)(c => toks(c).lastIndexWhere(t => Dashes.contains(t.text)))
        if (dash >= 0 && keepsWord(dash + 1, 0)) (dash + 1, 0) else (0, 0)
      } else {
        val texts = names.map(toks(_).map(_.text.toLowerCase(Locale.ROOT)))
        val (p, s) = (common(texts), common(texts.map(_.reverse)))
        Seq((p, s), (p, 0), (0, s)).find { case (a, b) => (a > 0 || b > 0) && keepsWord(a, b) }.getOrElse((0, 0))
      }
    def text(c: String, ts: Seq[Token]): String =
      if (!ts.exists(!noise(_))) "" else c.substring(ts.head.start, ts.last.end).trim
    val shared = names.map(c => c -> text(c, kept(c, pre, suf))).toMap
    // a run of such words at an end goes if it is a phrase (three words or more) or ends at a
    // separator: "Images from Wiki Loves Earth 2026 …", "Wiki Loves Earth Ukraine 2026 - …"; one or
    // two words stay ("Image sets …", "Images of people …")
    def trimStart(ts: Seq[Token]): Seq[Token] = {
      val run = ts.takeWhile(noise)
      if (run.sizeIs >= 3 || run.lastOption.exists(_.word.isEmpty)) ts.drop(run.size) else ts
    }
    val first = names.map { c =>
      c -> (if (pre > 0 || suf > 0) shared(c) else text(c, trimStart(trimStart(kept(c, 0, 0)).reverse).reverse))
    }
    def clashing(named: Seq[(String, String)]): Set[String] =
      named.filter(_._2.nonEmpty).groupBy(_._2.toLowerCase(Locale.ROOT)).values.filter(_.sizeIs > 1).flatten.map(_._1).toSet
    val clash = clashing(first)
    val second = first.map { case (c, n) => c -> (if (clash(c)) shared(c) else n) }
    val still = clashing(second)
    val named = second.map { case (c, n) => c -> (if (still(c)) c else n) }.toMap
    groups.flatMap(g => g.map(_ -> named(g.head))).toMap
  }

  /** The name tracks' names (rule 7's last case): each first round's name without the contest's
    * own words at its ends, as a category's ([[scopeNames]] step 2: its name, year, country, years,
    * "in", separators, in any case, where they make a run of three words or more or end at a
    * separator): "Wiki Loves Earth 2026 in Kyrgyzstan 1 round" → "1 round", "шорт лист Wiki Loves
    * Earth 2026 in Kyrgyzstan" → "шорт лист". The whole name where nothing else is left, or where
    * two names would become one.
    */
  private[services] def roundNames(names: Seq[String], boilerplate: Set[String]): Map[String, String] = {
    def noise(t: Token) = t.word.isEmpty || t.word.matches("\\d{4}") || boilerplate.contains(t.word) ||
      CommonWords.contains(t.word)
    def trimStart(ts: Seq[Token]): Seq[Token] = {
      val run = ts.takeWhile(noise)
      if (run.sizeIs >= 3 || run.lastOption.exists(_.word.isEmpty)) ts.drop(run.size) else ts
    }
    val stripped = names.distinct.map { n =>
      val ts = trimStart(trimStart(tokens(n)).reverse).reverse
      n -> (if (!ts.exists(!noise(_))) n else n.substring(ts.head.start, ts.last.end).trim)
    }
    val clash = stripped.groupBy(_._2.toLowerCase(Locale.ROOT)).values.filter(_.map(_._1).distinct.sizeIs > 1)
      .flatten.map(_._1).toSet
    stripped.map { case (n, s) => n -> (if (clash(n)) n else s) }.toMap
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

    // a label groups by exact match after trimming (case, accents and inner white space count, as
    // round.track compares them, utf8mb4_bin, and Round.trackLabel stores them): "second look" and
    // "Second look" are two tracks, each named as spelled; only Main is matched in any case
    def label(r: Round): Option[Track] =
      r.track.map(_.trim).filter(_.nonEmpty).map { l =>
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
      r.category.flatMap(categoryOf).filter(c => !contestCategory.exists(_.equalsIgnoreCase(c)))

    // the roots' categories' names, the part that tells them apart: from all the roots' categories,
    // so that labelling a root (as the Main hint asks), or giving it a nomination or regions,
    // doesn't rename another root's category track (nor change its key and colour)
    val roots = rounds.filter(r => previous(r.getId).isEmpty)
    val scopes = scopeNames(roots.flatMap(rootCategory), context.boilerplate)

    // a media type names a track only if it tells the roots apart: the roots have more than one,
    // none set counting as all (a video round beside the others); one alone (WLE international's
    // one image round) leaves the name
    // (26.11.17.2:) none set counts as images, as contests' files are: a round set to images among
    // others with none set tells nothing apart (WLE Kyrgyzstan 2026's "Финальный раунд" was "Image")
    def mediaOf(r: Round): String =
      r.mediaType.map(_.trim).filter(m => m.nonEmpty && m != db.scalikejdbc.MediaType.All)
        .getOrElse(db.scalikejdbc.MediaType.Image)
    def media(r: Round): Option[String] =
      r.mediaType.map(_.trim).filter(m => m.nonEmpty && m != db.scalikejdbc.MediaType.All)
    val severalMedia = roots.map(mediaOf).distinct.sizeIs > 1

    // the name tracks' names: the roots' names without the contest's own words at their ends
    val names = roundNames(roots.map(_.description.trim), context.boilerplate)

    def rootScope(r: Round): Track =
      rootCategory(r).map(c => scopes.getOrElse(c, c)).filter(_.nonEmpty).map(Track(Kind.Category, _))
        .orElse(media(r).filter(_ => severalMedia).map(Track(Kind.Media, _)))
        .getOrElse(Track(Kind.Name, names.getOrElse(r.description.trim, r.description.trim)))

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
