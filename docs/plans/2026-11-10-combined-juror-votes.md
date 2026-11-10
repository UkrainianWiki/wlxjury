# Combined juror votes for jurors (plan)

**State:** plan only, **after 26.11** (the maintainer, 2026-11-10: "bring back the ability to see
combined juror votes"). Nothing is built. **Sources:** the code and history at `f339aa13` (the
tip of `stack/90-triage-fixes`, PR #273): `GalleryService`, `User.canViewOrgInfo`,
`GalleryController`, `LargeViewController`, `Secured.viewAsRefused`, `navbar.scala.html`;
`git log -S` on `juryOrgView` and `viewAs`; PR #206; [secure coding](../security/secure-coding.md)
("Rule: juror confidentiality"); the tests named below. Each claim was read in the code; the
history of what jurors *could* do before the own-votes change is from the code of its parent
commit, not from user reports.

## 1. What "combined votes" was, and what happened to it

**The feature.** The all-jurors view of a round: `asUserId == 0` in the gallery routes
(`/gallery/round/:r/user/0/...`, the "by rate" gallery, the file list) and the large view's
ratings of all jurors (`LargeViewController`, `large.scala.html`). It shows each file's combined
score and rank (the round's score rule, V90), for yes/no rounds the approvals and rejections, and
the votes of each juror.

**Before 2026-10-27.** The tool decided two things about it, in two places:
- *The menus* (`navbar.scala.html`) showed the all-jurors views and the juror menu only to
  `User.canViewOrgInfo(round)`: root, the contest's admins and organizers, and a juror when the
  round had `juryOrgView` on. That function has been this way since 2014 (`9b196d80`,
  2014-11-27, "jury_org_view"; `b5203f21`, 2014-12-07).
- *The routes* did not check it. `GalleryController` (at `62f4af61^` in production's history,
  `398fc43d^` in the stack) called only
  `isNotAuthorized` (is the user in the round's contest and, for a juror, in the round) and
  `getAsUser(asUserId, user)`. So a juror of a round could open `/gallery/round/R/user/0/page/1`
  (all jurors together) or `/user/<another juror>/` by typing the URL, and the large view's
  ratings the same way. Nothing linked to them for a juror, but nothing refused them.

**The change.** In production's history, `62f4af61` ("Authorize the large image view by round
and juror", which added `viewerRounds`) and `b007de1e` ("Show jurors only their own votes", which
added `OtherUsersVotes`), both 2026-10-27 and ancestors of production's `f9c31ce5`. In the stack
it is #206 (`398fc43d`, the same day, "Authorization denials and error pages, own votes only,
votes by POST with CSRF"; the stack's CHANGELOG: "clear access-denied pages; jurors see only
their own votes; CSRF on votes (#206)"); `398fc43d` itself is not an ancestor of `f9c31ce5`. It added
`GalleryService.viewerRounds` / `mayViewRound` / `viewAs`: a juror sees only their own votes;
another juror's votes, or all together (`asUserId == 0`), only if `canViewOrgInfo`, and only of
a juror of the round, otherwise 403 (`OtherUsersVotes`, with a reference) or 404
(`NotRoundJuror`). A security fix: the votes and the authors are the organizers' information
(secure coding, "Rule: juror confidentiality"; a participant must not find and lobby a juror).

**Now, at `f339aa13`.**

| Who | Own votes | Combined votes (`asUserId == 0`) and other jurors' votes | Authors, juror menu, statistics |
|---|---|---|---|
| Root | yes | yes (of a juror of the round) | yes |
| The contest's admin or organizer | yes | yes | yes |
| A juror of a round with `juryOrgView` | yes | yes | yes |
| A juror of any other round | yes | **no**: 403 with a reference | no |
| Anyone else (not in the contest, not in the round) | no | no | no |

**`juryOrgView` is a dead switch.** The column `round.jury_org_view` has existed since V3 and
the code honours it, but nothing sets it: it is not on the round form (`EditRound`,
`editRound.scala.html`), and nothing else in `app/` sets it: `Round` only reads the column and
writes the round's value back, `false` by default. It can be turned on only by SQL. It is also all-or-nothing: it opens the combined votes *and*
every other juror's votes, the authors, the juror names and the statistics. The user guides say
"older rounds may let jurors see the organizers' view"; that is the only trace. Tests that pin
it: `OwnVotesSpec` ("[J-27]", "[J-28]"), `CoiAuthorizationSpec`, `CriteriaResultsPageSpec`
("a juror of a juryOrgView criteria round [J-28]"), `MutationAuthorizationSpec`.

**Two more facts that shape the options.**
- A **stopped round is hidden from its jurors** (`viewerRounds` lists `Round.activeRounds(user)`).
  So "after the round is closed" is not something a juror can reach today, whatever the setting.
- Comments are public to the round's jurors already (the guide says so); only votes and authors
  are confidential.

## 2. What to build: options

**Who sees the combined votes**

- **A. Only organizers, admins and root (today).** No work. Jurors see other jurors' work only
  through the organizers.
- **B. Jurors, always, in every round.** Restores the pre-#206 reach, now on purpose. Risks:
  herding (a juror sees the others' marks before voting), and it makes the jurors' votes visible
  to each other, which the confidentiality rule was written against.
- **C. Jurors, per round, when its admins choose, aggregate only.** A new round setting
  (default off). With it on, a juror may open the combined view of that round, which shows each
  file's score, rank and, for a yes/no round, the counts of approvals and rejections: never which
  juror voted what, never the authors, never the juror names. (On a file only two jurors rated,
  the aggregate minus your own vote is the other juror's vote, without saying who that is: see
  "Considered and dropped" below.)
- **D. Jurors, once the round is stopped, aggregate only.** C's content, shown only after the
  round has ended, so combined votes can't steer a live round. Needs stopped rounds to stay
  visible to their jurors, read-only (§3).
- **E. Reuse `juryOrgView`.** Give the existing switch a form field. Cheapest, but it opens
  per-juror votes, authors and juror names too, which is more than "combined votes".

**Recommendation: C and D together** ("results shared with jurors", per round, off by default,
shown after the round stops), aggregate only, plus the juror's own vote. *Why:* it gives what
was asked (combined votes) with the smallest widening of what jurors may see; the stop
condition keeps combined votes from steering the round itself; the
per-round choice keeps contests that don't want it as they are; and per-juror votes stay behind
`juryOrgView`, which keeps its meaning (a later change may retire it).

## 3. Decisions for the maintainer

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Who may see combined votes | A, B, C, D, or both C and D, E above | C and D together |
| D2 | When | any time / only after the round is stopped | only after it is stopped (`Round.openCond`, §4); a later round on the same files is the admin's call (§10) |
| D3 | What is combined | aggregate (score, rank, counts) / plus each juror's vote | aggregate only |
| D4 | Does a stopped round stay visible to its jurors (read-only) when the setting is on | yes / no, show the results elsewhere | yes: a read-only gallery and large view of that round, no voting |
| D5 | The setting's place and who sets it | round form, admins only (like the score rule) | round form, admins only, off by default |
| D6 | `juryOrgView` | leave / give it a form field / retire it | leave as it is; say in the docs that it is not set from the UI; decide retirement later |
| D7 | Also for yes/no rounds (approvals, rejections) | yes / rated rounds only | yes; both use the same aggregate queries |
| D8 | Show the juror's own vote beside the aggregate | yes / no | yes |

**Considered and dropped (the maintainer, 2026-11-10).** Two hiding rules were drafted and
dropped:
- *A minimum number of jurors or votes* (a round minimum, files with fewer votes left out of the
  ranked list, a "too few votes" line). The maintainer's reason: a juror can work out another
  juror's vote only when exactly two jurors rated a file. Most rated rounds have at least three
  jurors per file, and where a file is distributed to only two, the juror still can't see who the
  other juror is, so nothing is attributed to a person.
- *Hiding the results while a later round built from this one is open* (following the chain of
  previous rounds, a 403 for its jurors). Replaced by the admin's choice of when to switch the
  option on (§10).

## 4. Authorization rules the change must keep

From CLAUDE.md "Own votes" and secure coding §"juror confidentiality":
- A juror sees only their own votes **except** the aggregate this plan adds. `viewAs` keeps
  answering `OtherUsersVotes` (403 with a reference) for another juror's id, whatever the setting.
- The aggregate is `asUserId == 0` only, and only for a juror of the round: the same
  `User.isRoundJuror` check, 404 (`NotRoundJuror`) for a user who is not, never a `.get`.
- A user outside the contest, or without an active membership, gets nothing (`isNotAuthorized`
  as now; `Secured.inContest`, never switching the contest on a POST or poll).
- Authors (`showAuthor`), the juror menu (`User.findRoundJurorsToView`), the statistics page,
  the file list, the CSV and the conflicts page stay `canViewOrgInfo` / organizers only. The
  new permission is a separate function, `User.canViewRoundResults(round)` (root, organizer and
  admin as now; a juror of the round when the setting is on **and** the round is stopped), used
  only for the combined view, so `canViewOrgInfo` does not widen.
- **Stopped** is not open in `Round.openCond`'s sense (`active`, or `distributing`, or frozen
  while active: `freeze_count > 0 AND active_before_freeze`, a previous round of a running
  distribution), so a round only frozen by a distribution is not stopped.
- A vote still needs the round open to its jurors and the juror not stopped on it: the read-only
  view of a stopped round binds no vote controls and `selectWS` refuses as today.
- Denials go through `onUnAuthorized` with a `Secured.Denial` and are logged on `security.authz`.

## 5. Entry points

- **The gallery's views menu** (`navbar.scala.html`): for a juror, a "Results" entry next to the
  tabs when `canViewRoundResults`, pointing to the by-rate view with `asUserId == 0`.
- **The large view:** an "All jurors" box (`large.scala.html`) limited to the aggregate for a
  juror: the file's score, its divisor and, for a yes/no round, the approvals and rejections.
- **A stopped round in the juror's round list** (D4), marked as finished.
- **The round form** (admin): the option with its help text, next to the score rule.

## 6. Data and query impact

- One new column, `round.jurors_see_results BOOLEAN NOT NULL DEFAULT 0` (V95; `INSTANT`, a
  metadata-only change, like V92). `Round` reads and writes it; `EditRound` and the form get it.
- **No new queries or tables.** The combined view already runs for organizers on the same data:
  `vote` aggregates (V72–V74), the round's score rule (V90) and, once `roundImage.read` is on
  (off in 26.11), `round_image` counts. A juror's request costs what an organizer's does; the
  per-juror columns are simply not selected for a juror, so the view needs a juror variant of the
  by-rate page (`galleryByRate`, `GalleryController.listGeneric`), not a filter after the fact.
- **The counts shown** count votes, not rows: a `vote` row with `rate <> 0` (`VoteSql.voted`),
  as the organizers' counts do. Distribution creates a rate-0 row for every assigned file, and a
  cleared vote is 0 again (`VoteSql.unrated`); a criteria vote's `rate` is set only once every
  criterion is rated, and is 0 before (`CriteriaRate`, U5).
- Messages in four languages (en, fr, uk, ru).

## 7. Tests

Positive: a juror of a stopped round with the setting on opens the combined gallery and the
large view and sees score, rank and counts, with their own vote beside them; the round form
saves the setting (admins only); the counts leave out rate-0 rows and partly rated criteria
votes.
**Negative authorization (each a request, each asserting the status and that the body has none of
the forbidden content):**
- a juror, setting off, `asUserId == 0`: 403 `OtherUsersVotes` (as `OwnVotesSpec` [J-27] today);
- setting on, round still open: 403 (D2);
- setting on, a round only frozen by a running distribution (not stopped): 403;
- setting on, stopped, `asUserId = another juror`: 403;
- setting on, stopped, the page's HTML has no author names, no juror names or emails, no
  per-juror rates, and the file list and CSV answer 403;
- a juror who is not in the round, or from another contest, `asUserId == 0`: 404 or 403 as now;
- an organizer's or admin's view unchanged; the setting is refused from an organizer (only
  admins save the form: `MutationAuthorizationSpec`);
- a vote from the read-only stopped round is refused and stores nothing (`selectWS`).
Extend `OwnVotesSpec`, `MutationAuthorizationSpec` and `CoiAuthorizationSpec`; add a browser
story (`browser-test`, a juror results page, a new J story in `docs/testing/stories.md`).

## 8. Documentation

[Juror guide](../user/juror-guide.md) ("Your own votes only": say when results are shared, and
that the older "organizers' view" is a different thing); the organizer and admin guides (the
round form's option); a what's-new line after the release; secure coding ("Rule: juror
confidentiality": the new exception and why the aggregate carries no names); the stories
catalogue (a new J story). **Screenshots that would help (not regenerated):** the round form's
section with the new option (`organizer-score-rule-warning.png` shows the neighbouring options),
and a new shot of a juror's results page.

## 9. Size

**M**: about two days. A column and migration (small), the form and service (small), the juror
variant of the by-rate view and the large view's box (the bulk, medium), the stopped-round
read-only access for jurors (medium: `viewerRounds`, the gallery and large view must not offer
votes), four languages, about 16 tests (§7: 4 positive and 12 negative, counting each case of a
bullet as one test) plus the browser story, and the docs. If D4 is "no" (results shown outside
the gallery), the read-only access piece drops out, and the work is about one day.

## 10. Order and risks

After 26.11, in the queue as one batch with its own review. Risks: a view that leaks a name or a
per-juror rate (the negative tests in §7 exist to catch it); the stopped-round access widening
`viewerRounds` for other pages (restrict it to the two views, and test the round list); jurors
reading the results as final when organizers still decide (the help text says the organizers
choose the winners). Showing a round's results before a later round on the same files
(`Round.previousIds`) can influence the jurors who vote there, so the admin decides when to
switch the option on, and the option's help text says so.
