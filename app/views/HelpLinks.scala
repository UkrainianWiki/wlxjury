package views

import play.api.i18n.Messages
import play.twirl.api.{Html, HtmlFormat}

/** A section of the user guide that the tool links to: a topic page of docs/user and an explicit
  * `<a id>` in it (empty: the page's top).
  */
final case class HelpTopic(page: String, anchor: String)

/** The tool's help links into the user guide (docs/plans/2026-11-26-help-links-and-guide-split.md
  * §4): one table, a topic key to its page and id. The URLs live only here: a view names a key
  * (`@snippets.helpLink("tracks")`), the link text is the message `help.more.<key>`, and the guide is
  * linked as of this build's version ([[Guide.url]]). `test/views/HelpLinksSpec` checks that each
  * page and id exists, that each key has its texts in every language, and that the templates name
  * only keys of this table.
  */
object HelpLinks {

  val topics: Map[String, HelpTopic] = Map(
    // admins: settings on the round and jurors forms
    "tracks"               -> HelpTopic("tracks.md", "a-track-label"),
    "categories"           -> HelpTopic("round-files.md", "a-category-first"),
    "category-second"      -> HelpTopic("round-files.md", "a-category-second"),
    "category-options"     -> HelpTopic("round-files.md", "a-category-options"),
    "top-files"            -> HelpTopic("round-files.md", "a-top-files"),
    "top-files-binary"     -> HelpTopic("round-files.md", "a-top-files-binary"),
    "score-rule"           -> HelpTopic("scoring.md", "a-score-rule"),
    "distribution"         -> HelpTopic("round-jurors.md", "a-file-distribution"),
    // admins: sections
    "create-round"         -> HelpTopic("round-create.md", "a-create-round"),
    "choose-files"         -> HelpTopic("round-files.md", "a-choose-files"),
    "rejection-filter"     -> HelpTopic("round-files.md", "a-rejection-filter"),
    "leave-out-rounds"     -> HelpTopic("round-files.md", "a-leave-out-rounds-setting"),
    "worst-mark"           -> HelpTopic("scoring.md", "a-worst-mark"),
    "criteria"             -> HelpTopic("scoring.md", "a-criteria"),
    "add-files"            -> HelpTopic("round-files.md", "a-add-files"),
    "distribute-new-files" -> HelpTopic("round-files.md", "a-distribute-new-files"),
    "leave-out-conflicts"  -> HelpTopic("conflicts.md", "a-leave-out-conflicts"),
    "image-cache"          -> HelpTopic("contest-files.md", "a-image-cache"),
    "image-check"          -> HelpTopic("round-create.md", "a-image-check"),
    "review"               -> HelpTopic("round-review.md", "a-review"),
    "rounds-list"          -> HelpTopic("rounds.md", "a-rounds-list"),
    "add-juror"            -> HelpTopic("round-jurors.md", "a-add-juror"),
    "reassign-juror"       -> HelpTopic("round-jurors.md", "a-reassign-juror"),
    "pause-juror"          -> HelpTopic("round-jurors.md", "a-pause-juror"),
    "users"                -> HelpTopic("people.md", "a-users-page"),
    "mass-import"          -> HelpTopic("people.md", "a-mass-import"),
    "add-from-contest"     -> HelpTopic("people.md", "a-add-from-contest"),
    "add-person"           -> HelpTopic("people.md", "a-add-person"),
    "edit-person"          -> HelpTopic("editing-people.md", "a-edit-person"),
    "duplicates"           -> HelpTopic("editing-people.md", "a-duplicates"),
    "join"                 -> HelpTopic("editing-people.md", "a-join"),
    "account-changes"      -> HelpTopic("editing-people.md", "a-account-changes"),
    "invitations"          -> HelpTopic("invitations.md", "a-pending"),
    "share-link"           -> HelpTopic("invitations.md", "a-share-link"),
    "greeting"             -> HelpTopic("invitations.md", "a-greeting"),
    "import-files"         -> HelpTopic("contest-files.md", "a-import-files"),
    "series-admins"        -> HelpTopic("contest.md", "a-series-admins"),
    // organizers (and admins)
    "statistics"           -> HelpTopic("statistics.md", "o-statistics"),
    "round-changes"        -> HelpTopic("statistics.md", "o-round-changes"),
    "results"              -> HelpTopic("results.md", "o-results"),
    "comments-report"      -> HelpTopic("comments.md", "o-comments-report"),
    "conflicts"            -> HelpTopic("conflicts.md", "o-conflicts-page"),
    "contest-files"        -> HelpTopic("contest-files.md", "o-contest-files"),
    "monuments"            -> HelpTopic("monuments-authors.md", "o-monuments"),
    "authors-list"         -> HelpTopic("monuments-authors.md", "o-authors-list"),
    // jurors: the gallery's Help, their contents page
    "guide"                -> HelpTopic("juror-guide.md", ""),
    // root
    "root"                 -> HelpTopic("root.md", "a-root"),
    "contest-settings"     -> HelpTopic("contest.md", "a-contest-settings"),
    "normalize-authors"    -> HelpTopic("root.md", "a-normalize-authors")
  )

  /** The message key of a topic's link text. */
  def messageKey(key: String): String = s"help.more.$key"

  /** The message key of the hidden words saying that the guide is in English. */
  val EnglishKey = "help.english"

  /** The topic's URL, as of this build's version; None for a key not in the table. */
  def url(key: String, version: String = Guide.version): Option[String] =
    topics.get(key).map(t => Guide.url(t.page, t.anchor, version))

  /** The link's content: its text, then " (guide in English)" for screen readers, and the mark of a
    * link that leaves the tool with its " (opens in a new tab)" ([[ExternalLinks.withMark]]): the
    * text's last character, the hidden words and the mark in one span that doesn't wrap.
    */
  def content(key: String, url: String)(implicit messages: Messages): Html = {
    val english = s"""<span class="visually-hidden"> (${HtmlFormat.escape(messages(EnglishKey)).body})</span>"""
    Html(ExternalLinks.withMark(HtmlFormat.escape(messages(messageKey(key))).body,
      english + views.html.snippets.externalMark(url).body))
  }
}
