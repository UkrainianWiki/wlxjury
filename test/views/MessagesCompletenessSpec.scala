package views

import controllers.{ListOrder, LoginController, Theme}
import db.scalikejdbc.{MediaType, User}
import org.intracer.wmua.{BinaryOrder, CategoryOperation, Criterion}
import org.specs2.mutable.Specification
import play.api.i18n.Messages.{parse, UrlMessageSource}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Every message key the app uses is in all four messages files, and the files have the same
  * keys, so no page falls back to a raw key or to English in one language.
  *
  * The keys are read from the sources in `app/`: literal `Messages("…")` calls, string literals
  * passed to the helpers that look them up (`main`, `snippets.submit`, `errorPage`, …), and,
  * for keys built at run time, the values they are built from ([[dynamicKeys]]). A new helper
  * taking a key, or a key built in a new way, needs a pattern or an entry here.
  */
class MessagesCompletenessSpec extends Specification {

  private val langs = Seq("en", "fr", "uk", "ru")

  private def file(lang: String) = Paths.get(s"conf/messages.$lang")

  /** A file's messages, parsed by Play's own parser (comments, continuations, escapes). */
  private def messagesOf(lang: String): Map[String, String] =
    parse(UrlMessageSource(file(lang).toUri.toURL), s"messages.$lang") match {
      case Right(messages) => messages
      case Left(error)     => throw error
    }

  private lazy val parsed: Map[String, Map[String, String]] = langs.map(l => l -> messagesOf(l)).toMap
  private lazy val files: Map[String, Set[String]]          = parsed.view.mapValues(_.keySet).toMap
  private def values(lang: String): Map[String, String]     = parsed(lang)

  /** The keys of a file's lines, repeated ones included (the parser keeps the last). */
  private def keyLines(lang: String): Seq[String] =
    new String(Files.readAllBytes(file(lang)), UTF_8).linesIterator
      .flatMap("^\\s*([a-zA-Z0-9$_.-]+)\\s*=".r.findFirstMatchIn(_).map(_.group(1)))
      .toSeq

  /** The app's Scala and Twirl sources, without Twirl comments and whole-line `//` comments. */
  private lazy val sources: Seq[(String, String)] = {
    val walk = Files.walk(Paths.get("app"))
    try
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && (p.toString.endsWith(".scala") || p.toString.endsWith(".scala.html")))
        .toList
        .map { (p: Path) =>
          val text = new String(Files.readAllBytes(p), UTF_8)
            .replaceAll("(?s)@\\*.*?\\*@", "")
            .replaceAll("(?m)^\\s*//.*$", "")
          p.toString.replace('\\', '/') -> text
        }
    finally walk.close()
  }

  private val str = "\"([^\"\\\\]+)\""

  /** A literal key in a lookup; one followed by `+` is a prefix ([[prefixes]]). */
  private val lookup = ("\\b(?:Messages|messages)\\s*\\(\\s*" + str + "(\\s*\\+)?").r

  /** String literals passed to code that looks them up as keys. */
  private val keyArguments = Seq(
    "\\b(?:main|mainAdmin)\\(\\s*" + str,                             // a page's title
    "snippets\\.(?:submit|collapsible)\\(\\s*" + str,
    "snippets\\.button\\([^\\n]*,\\s*" + str + "\\)",
    "sideSection\\(\"[^\"]*\",\\s*" + str,
    "errorPage\\([^\\n]*?" + str + ",\\s*" + str,                     // title and message
    "SelectionItem\\(" + str + "(?![^\\n]*localized = false)",
    "RegionMenu\\(\"[^\"]*\",\\s*" + str + ",\\s*" + str,
    "AccessSuggestion\\([^\\n]*?\"(access\\.[a-z.]+)\"",
    "reRender\\([^,\\n]+,\\s*" + str,
    "withGlobalError\\(\\s*" + str,
    "withError\\(\"[^\"]+\",\\s*" + str,
    "verifying\\s*\\(\\s*" + str,
    "@link\\([^\\n]*?\"(pager\\.[a-z]+)\"",
    "\"((?:error|vote\\.error|access\\.denied)\\.[a-zA-Z.]+)\"" // error keys, often held in a value first
  ).map(_.r)

  /** Keys built from a prefix and a value: the values each prefix takes. */
  private val prefixes: Map[String, Seq[String]] = Map(
    "role."  -> (User.FORM_ROLES.filterNot(_ == User.NO_ROLE) :+ User.ROOT_ROLE), // accessDenied
    "theme." -> Theme.All.map(_.value),
    "coi.requalify.reason." -> db.scalikejdbc.RoundConflicts.RequalifyReason.All, // round/conflicts.scala.html
    "coi.code." -> db.scalikejdbc.RoundImageJdbc.DqCode.All,                        // round/conflicts.scala.html
    "coi.event." -> db.scalikejdbc.RoundImageEventJdbc.Action.All,                  // round/conflicts.scala.html
    "coi.status." -> (db.scalikejdbc.RoundImageJdbc.Status.All :+ "removed"),       // round/conflicts.scala.html
    // linkStatus.scala.html: AccountToken.Status.key, and its time's text
    "link.status." -> Seq("active", "used", "expired", "revoked").flatMap(s => Seq(s, s"$s.at")),
    "link.purpose." -> db.scalikejdbc.AccountTokenJdbc.SetsPassword,                // editUser's set-password links
    "account.change.field." -> db.scalikejdbc.AccountChangeJdbc.Fields              // accountChangeTable.scala.html
  )

  /** Keys looked up from a value rather than a literal. */
  private val dynamicKeys: Seq[String] = Seq(
    User.FORM_ROLES.map("role." + _),                           // every role name, as Messages("role." + r)
    Seq("role." + User.ROOT_ROLE),                              // editUser offers root to root
    MediaType.mediaTypes,
    CategoryOperation.values.map(_.labelKey),
    BinaryOrder.all.map(_.messageKey),                          // the navbar's and round form's binary orders
    Seq(BinaryOrder.LegacyMessageKey),                          // the round form's top-N of a round from before V72
    LoginController.ErrorPageKeys.toSeq,
    Seq("round", "juror").flatMap(w => Seq(s"$w.start", s"$w.stop")), // snippets.startStop
    Seq("selected", "rejected", "unrated", "rated"),                   // voteMoved, navbar tabs
    Seq("no"),                                                         // imageFiltering: "No".toLowerCase
    Seq("", ".resumed", ".distributing").map("round.resubmitted" + _), // RoundService.Resubmitted.messageKey
    Seq("round.distribute.running", "round.distribute.running.files", "round.distribute.resumed.files",
      "round.still.distributing", "import.already.running"),        // flashed by RoundController, ImageController
    Seq("creation", "distribute").map(w => s"round.$w.failed.reference"), // RoundController.saveRound
    Seq("missing", "not.failed", "no.handler", "duplicate").map("job.retry." + _), // JobScheduler.retry
    Criterion.ShippedKeys,                                                 // V83's criteria, Criterion.name
    Seq("full", "exists").map("criteria.catalogue." + _),                  // CriterionJdbc.AddRefusal
    Plural.Forms.map("comments.count." + _),                               // CommentCount.label, by plural form
    Seq("authors.count.", "authors.files.count.").flatMap(k => Plural.Forms.map(k + _)), // authors.scala.html
    Plural.Forms.map("comments.report.files." + _),                        // CommentCount.files, by plural form
    Seq(ListOrder.LatestComment.messageKey),                               // commentsReport's order menu
    Seq("round", "contest").flatMap(w => Seq(s"comments.report.scope.$w", s"comments.report.empty.$w")) // commentsReport
  ).flatten

  /** Texts in a key's place that are shown as they are, not keys, each with its reason. */
  private val literalTexts: Map[String, String] = Map(
    "Jury role"            -> "rounds.scala.html: inside an HTML comment, never shown",
    "Min selected"         -> "rounds.scala.html: inside an HTML comment, never shown",
    "Max selected"         -> "rounds.scala.html: inside an HTML comment, never shown",
    "Recommended selected" -> "rounds.scala.html: inside an HTML comment, never shown"
  ) ++ Seq("id", "place", "city", "user", "coordinates", "page", "type", "commons", "resolution")
    .map(_ -> "monuments.scala.html: the unlinked /monuments list, headed by the field names")

  private lazy val literalKeys: Map[String, Set[String]] =
    sources
      .flatMap { case (file, text) =>
        lookup.findAllMatchIn(text).flatMap { m =>
          val key = m.group(1)
          if (m.group(2) == null) Seq(key -> file)
          else prefixes.getOrElse(key, Seq(s"$key<unknown values: add to prefixes>")).map(v => (key + v) -> file)
        } ++ keyArguments.flatMap(_.findAllMatchIn(text).flatMap(_.subgroups).filter(_ != null).map(_ -> file))
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  private lazy val usedKeys: Set[String] = literalKeys.keySet ++ dynamicKeys

  "the messages files" should {

    "have the same keys in every language" in {
      val en = files("en")
      foreach(langs.tail) { lang =>
        val keys = files(lang)
        (en.diff(keys).toSeq.sorted aka s"keys missing in messages.$lang" must beEmpty) and
          (keys.diff(en).toSeq.sorted aka s"keys only in messages.$lang" must beEmpty)
      }
    }

    "define each key once" in {
      foreach(langs) { lang =>
        keyLines(lang).groupBy(identity).collect { case (k, ks) if ks.size > 1 => k } aka
          s"keys repeated in messages.$lang" must beEmpty
      }
    }

    "have the same placeholders as in English" in {
      val en = values("en")
      // MessageFormat: '' is a quote, {n} or {n,type} an argument
      def args(v: String) = "\\{(\\d+)".r.findAllMatchIn(v.replace("''", "")).map(_.group(1)).toSet
      foreach(langs.tail) { lang =>
        values(lang).collect { case (k, v) if en.get(k).exists(e => args(e) != args(v)) => k }.toSeq.sorted aka
          s"keys with other placeholders in messages.$lang" must beEmpty
      }
    }

    "have every key the app uses" in {
      val en      = files("en")
      val missing = usedKeys.diff(en).diff(literalTexts.keySet).toSeq.sorted
      missing.map(k => s"$k (${literalKeys.getOrElse(k, Set("dynamicKeys")).mkString(", ")})") aka
        "keys used in app/ but missing in messages.en" must beEmpty
    }

    "list as literal texts only texts the app still uses that are not keys" in {
      (literalTexts.keySet.diff(literalKeys.keySet).toSeq.sorted aka "literal texts no longer used" must beEmpty) and
        (literalTexts.keySet.intersect(files("en")).toSeq.sorted aka "literal texts that are keys" must beEmpty)
    }

    "be read from the sources at all" in {
      (literalKeys.size must be_>(200)) and (literalKeys.keySet must contain(allOf("theme.dark", "role.jury", "pager.next")))
    }
  }
}
