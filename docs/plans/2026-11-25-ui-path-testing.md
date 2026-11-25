# UI-path testing: offered actions that match allowed actions

**Status:** draft, for the maintainer's decisions (§5); Q1, Q4, Q6 and the setup steps (§4 step 1, superseding Q10) decided 2026-11-25 **[user]**. Written 2026-11-25 from the maintainer's
request of the same day: "we have been consistently seeing inconsistent UI paths". Read-only survey
of master at `5d72c4d8` (after the 26.11.16 build); no code changed, no test run. References are to
files and functions; line numbers are of that commit and drift.

Marks: **[user]** decided by the maintainer; a default is this plan's, open to the maintainer.

**The examples given:**
- redistributing a juror's files to that same juror;
- redistributing when no other juror exists;
- "Distribute to jurors" reachable only from the round's review page, not from Edit round (#186);
- the rating type locked in review although nothing is distributed (#184);
- the category field hidden twice (#170, fixed in 26.11.16);
- on production, 2026-11-25 08:05–08:13 CET, an admin got 11 × `POST /admin/rounds/save` → 400 in
  a row before a save succeeded.

Release labels (26.11.11, 26.11.13, 26.11.13.1, 26.11.14) are from `CHANGELOG.md`; the repository's
git tags start at `26.11.15`, and every fix below is in it.

**What they have in common:** each page decides on its own whether to show an action (a Twirl
condition), and each controller or service decides on its own whether to allow it (a permission, a
refusal under the round's lock). The two are written separately, at different times, and nothing
checks that they agree. Most fixes so far added the missing condition to one view and a spec for
that one case (§0). This plan proposes one source of truth per action and a table-driven check.

## 0. Known cases on master (26.11.16)

| # | Case | Fixed by | Guard spec | Still open |
|---|---|---|---|---|
| 1 | "Add juror…" listed people already jurors of the round | `92835218` (#122, 26.11.15): the add page lists only the contest's jurors outside the round; a stopped juror is restarted from Round statistics ("Start with a fair share") | `test/controllers/RoundJurorsAuthorizationSpec.scala:148` ("offer only the contest's jurors outside the round, never an active or a stopped juror"); `test/services/distribution/RoundRebalanceJobSpec.scala:397` (an active juror refused on page and POST) | • "Add juror…" is linked for every round its admin sees (`roundStatTable.scala.html:164`, `mayPause` only), also stopped, in review and being created; the page then refuses (not running, frozen or legacy: `RoundRebalanceHandler.refusal`). Reassign is gated by `running` (line 27).<br>• When every contest juror is already in the round, the link still shows and the page has nobody to offer (**to verify** on the page). |
| 2 | Reassign offered a juror's files to that same juror | `31db48be` (archive #368, 26.11.13): the receivers are the round's other jurors (`RoundRebalanceService.reassignPreview`: `others = j.jurors.filterNot(from)`; `reassignRefusal`: `to = receivers.filterNot(_ == from)`); add-juror donors likewise exclude the juror added (`RoundRebalanceService.preview`) | None that names it: `RoundReassignAuthorizationSpec.scala:102` checks the receivers shown, `:286` refuses other bad receivers, but no example posts `receiver = from` or asserts the juror is absent from the receiver list | • A posted `receiver = from` is silently dropped, not refused: harmless, but no spec pins either behaviour.<br>• Add an explicit example on page and POST for both reassign (receiver) and add (donor). |
| 3 | "Reassign files…" offered when no other juror can take the files | `fc5f3ad3` (archive #383, hotfix 26.11.13.1): `RoundReassign.enoughReceivers` (N > 0: at least N other jurors, stopped ones count since a ticked one is restarted; N = 0: at least one other *active* juror); the page, the POST and the job's first step refuse with `AloneKey` | `RoundReassignAuthorizationSpec.scala:164` (alone with N = 1 and N = 0; the other juror with no active jury membership, deleted, or stopped with N = 0; N = 2 with one other) and `:193` (N = 1 with a stopped other, N = 2 with two: offered); `RoundReassignJobSpec.scala:479` (the job's own check) | • **One active plus stopped ones:** covered (stopped count for N > 0, not for N = 0).<br>• **Stopped round_user row:** covered (`juror2 -> false`); a NULL `active` (before V108) reads as stopped, as everywhere.<br>• **Series-derived jurors:** do not exist: series grants derive admin and organizer roles only (`Secured.derivedRoleIn`); receivers are read from real `user_contest` jury rows (`User.contestMembers`), so nothing to cover.<br>• **Open:** a receiver whose account is in an account join (J-2) is not among the examples. |
| 4 | Criteria UI shown where criteria can't be edited | `3fdff615` (archive #355, 26.11.11): an existing yes/no or comments-only round's form has no criteria and no worst mark (`unratedRound`); `9362cfe5` (archive #386, 26.11.14, "A started round's criteria can no longer be changed"): a rated round running or with a vote lists its criteria read only (`lockedCriteria` → `RoundCriterion.started`), and `RoundCriterion.update` refuses a change | `RoundEditPageSpec.scala:476`, `:498`, `:511` (also on the form shown again with an error), `:528`; `RoundCriteriaSpec.scala:232`, `:406`, `:436`, `:464` (running, or frozen while active: locked; editable again once stopped before a vote), `:365` (re-apply) | • **In review:** criteria editable (not started) but the rating type locked (`editRound.scala.html`: `rates` readonly by `noEdit = !newRound`): #184.<br>• **Paused by a job (frozen while active):** covered.<br>• **Re-apply:** criteria written in its transaction, refusal covered.<br>• **After a refused re-apply save:** the form comes back without re-apply mode (§0.5), so the filters turn read only and the next press is a plain Save. |
| 5 | 11 × 400 on `POST /admin/rounds/save` | not fixed (26.11.16 and master unchanged on this path) | none for the form shown after a 400. Individual refusals return 400 in `RoundExcludeRoundsFormSpec`, `RoundWorstMarkFormSpec`, `RoundCriteriaSpec`, `RoundEditPageSpec:183` (top-N below 1) and `RoundReviewJurorsReapplyPageSpec:159`, but they check the status and message only, not the mode, open sections or menus of the form that comes back | see §0.5 |
| 6 | A successful save isn't confirmed, so a user who still sees form fields can't tell the round was saved | not fixed | none | • **Create:** `saveRound`'s `Created` branch redirects to the jurors form ("Choose jurors next", `RoundReviewController.jurors`) or to the rounds list ("Review images first", `toRoundsList`) with **no success flash** in either case. Both pages would render one (`jurorsForm.scala.html:31`, `rounds.scala.html`); nothing sets it. The jurors page's heading is "Round N (description): jurors and file distribution" (`round.jurors.heading`), which doesn't say the round was just saved or which step of its setup this is.<br>• **Edit save of an existing round:** `saveEdit` → `flashing()` back to the rounds list also has **no success line**; only side effects flash (conflicts disqualified, distribution errors). A **re-apply** does confirm (`round.reapply.started` on the review page). |
| 7 | The need to distribute is stated only on the review page: admins don't know a round in review is invisible to jurors until distributed | not fixed | none | What the pages say today:<br>• **Review page** (`roundReviewHead.scala.html:22`): "Images ready: N. The jurors don't see the round until you distribute it." (`round.review.ready`), plus the Distribute button; the only place that says it.<br>• **Rounds list** (`rounds.scala.html:109–113`): "Images ready: N · Review · Distribute to jurors" in the job column, while the Active column shows "No" and a **Start** button (which is refused, §2.1).<br>• **Round statistics** (`roundStat.scala.html:105`): only a "Review images" link.<br>• **Edit round**: only a "Review images" button; nothing about distribution (#186). |

### 0.5 Which saves return 400, and what the admin sees

`RoundController.saveRound` answers 400 in two ways:

1. **The form doesn't bind** (`editRoundForm.fold(formWithErrors => …)`): `views.html.editRound` is
   rendered *directly*, not through `roundFormView`. Field errors: `rates`, `number`, `contest`
   missing or not a number; `roles` not a contest role; `topImages < 1`; `averageRule`,
   `rejectedMode`, `categoryCombine`/`categoryClause` unknown; a category box that isn't a legal
   title (`WikiTitle.InvalidKey`). Form-level (global) errors: `error.previous.rounds.mixed.type`,
   the category rule (`error.category.first.required` / `.second.required`), criteria (count,
   distinct, unknown, rated only), the rejected-by pair, the worst mark, and the rounds to leave
   out (`error.exclude.rounds.*`, including **`.busy`**: a chosen round is still being distributed
   or its distribution failed).
2. **Our own checks refuse** (`reRender`, through `roundFormView`): unreadable "Add files" lines,
   a retired contest, rejected-by without a source, criteria on a non-rated round, the worst mark,
   a criteria change on a started round, a re-apply refused (not in review, a vote, a job), a
   creation refused by `RoundController.refusal` (e.g. **`error.previous.round.rebalancing`**: a
   previous round has a juror add or reassign job running) or by `ExcludeRoundsRefused`.

**Where the reason shows.** `snippets.formError` shows only the *first* global error, at the top of
the form; field errors show under their field (`bs.feedback`), and the category choice's under
"More category options". A form-level error about the category boxes, the criteria, the
previous rounds or the rounds to leave out is therefore not next to its control, and a second
global error is not shown at all. **No binding error and no `reRender` refusal is logged** (only `failed`
and the service refusals log), so production's access log shows the 400s but not why.

**What happened** (production access log and app log, 2026-11-25; production ran 26.11.15, and
26.11.16 and master don't change this path):
- 08:01:54 the new-round form was saved (303): round 1991 was created then.
- 08:05:15 a save whose referer was the 08:00 form, with no GET in between: the admin pressed Back
  and resubmitted the cached new-round form. 400.
- 08:05–08:13 ten more saves from the 400 page, each 400, each with the original form token. The
  bodies were large (a long pasted "Add files" list).
- 08:15:41 a save answered 303, but only by the duplicate-form guard (WARN "already created from
  this form; not creating it a second time"): nothing was created and **the changes were silently
  dropped**.
- 08:36 the admin opened Edit round 1991 and used Re-apply filters, which worked; distributed at 08:52.

**Why the 400s.** None of the 11 was logged, and every service refusal logs a WARN, so the
service refusals (including the wait `error.previous.round.rebalancing` and
`ExcludeRoundsRefused`) are ruled out. Among the silent paths left are the `formWithErrors`
branch, `files.list.unreadable` and `error.rejected.by.no.source`. The other plain `reRender`
refusals in `RoundController` (26.11.15) are unlogged too: a retired contest, criteria or the
worst mark on a round that isn't rated, a criteria refusal, a re-apply of a round not in review,
and a distribution already running. Most likely (medium
confidence) a **field error inside the "Media filtering" card**: the card is rendered collapsed
on every render, the 400 included, and holds every filter field (previous rounds, rejected-by,
top-N, the category boxes, the rounds to leave out, media type). The error showed only inside
the closed card, so each submit came back with the values kept and no visible
message.

**Other problems of the same branch** (from the code, not seen in this incident):
- **The form comes back different.** The `formWithErrors` branch passes neither `reapply` nor
  `regions`, `specialNominations`, `coi`, `waiting`, `distributionJob`, `scoreRuleWarning` or
  `storedCriteria`, and lists the contest's jurors, not the round's. On a round in review the
  filters then render read only, the invalid value is posted again on each Save and can't be
  corrected there; on a new round the region and nomination menus disappear and a resubmission
  drops those filters.
- **Stored filters that fail today's validation** (a legacy category rule or title after #170)
  would refuse every save of that round's edit form, with read-only fields.
- **Waits shown as errors** (`error.exclude.rounds.busy` as a form error,
  `error.previous.round.rebalancing` as a refusal) make the admin press Save until a job ends.

**Fix candidates** (all in the 26.11.17 refusal-UX item, §4):
1. Open every section that contains an error, and put a linked error summary at the top (every
   error, each linking to its field).
2. Render the error branch through `roundFormView`, so the form keeps its mode, menus and jurors.
3. Log each refused save once (message keys only, no input text) as `form_refused`, and a dropped
   resubmit as `resubmit_dropped`: both lines are defined by the problem-monitoring plan (draft
   PR #191, slice M-1), not by a logger of this plan.
4. **[user] 2026-11-25: the stale new-round form is never shown again** (§5 Q6). On submit (or
   on saving) the form page's history entry is replaced by a URL that resolves the form token: if
   that form already saved a round, it redirects to that round's Edit page with one line, "Round N
   is saved. You can change its settings here."; if not (the submit was refused), it shows the
   form as usual. Today validation runs before the token check, so **the server must check the token
   first**.
5. No duplicate can be made this way and nothing is dropped silently: Back lands on the saved
   round's Edit page. Another round is started with the usual "Add round" button.
6. Catch bad input up front: unreadable list lines on paste; min and required hints for top-N and
   the category boxes.

## 1. Inventory: round and juror actions by state, role and condition

States as stored (`Round.lifecycle`, V105) with the flags that refine them: **draft** (being
created: `distributing`, its fill running or failed), **review** (`inReview`: paused, distributing,
filled), **active** (open to jurors), **frozen** (a job holds it: `freezeCount > 0`, active or not),
**stopped** (paused, not distributing), **cancelled** (creation cancelled). `finalized` is not
written yet. Roles: root, admin (real `user_contest` row), series admin (derived admin, opts in per
action: `derived = true`; all the actions below opt in, and `Secured.administersRound` reads the
derived role too), organizer (read only + comments), juror.

Cells: **O/A** offered and allowed; **O/R** offered, refused with a message; **—/A** allowed but not
offered on that page; **—** neither. ⚠ marks a mismatch (§2). **Cells with "?" or "to verify" are
unchecked**: read from the views only, the refusal not traced; the matrix spec (§3 a) settles them.

### 1.1 Admin (root and series admin the same, unless noted)

| Action (page: control) | draft | review | active | frozen | stopped | cancelled |
|---|---|---|---|---|---|---|
| Rounds list: Start/Stop (`snippets.startStop`) | O/R ⚠ "still distributing" | O/R ⚠ says it "starts by itself", but review needs Distribute | O/A (stop) | O/A, applied when the job ends | O/A (start) | O/R ⚠ (to verify) |
| Rounds list: Review · Distribute to jurors | — | O/A (no active job) | — | — | — | — |
| Rounds list: Release freeze | — | — | root, if left frozen | root, if left frozen | root, if left frozen | — |
| Edit round: Save | O/A | O/A as **Re-apply filters** | O/A | O/A | O/A | O/A (to verify) |
| Edit round: Distribute new files / waiting top-up | O/? | — (re-apply instead) | O/A | O/R (job) | O/A (to verify) | O/? |
| Edit round: link to Distribute (jurors form) | — | **—/A ⚠ #186** (only the Review link; the jurors form is one step further) | — | — | — | — |
| Edit round: rating type | readonly | **readonly ⚠ #184** | readonly | readonly | readonly | readonly |
| Edit round: criteria | editable | editable | read only if voted/running | read only | editable before a vote | editable |
| Review page: Distribute | — | O/A (no active job) | — | — | — | — |
| Jurors form: Distribute | — | O/A; 0 jurors in contest: link to Users | —/R | —/R | —/R | —/R |
| Statistics: juror Start/Stop | O/R (job) | O/? ⚠ (no jurors yet; to verify) | O/A | O/R "busy" | O/A + warning | O/? |
| Statistics: Add juror… | O/R ⚠ refuses (not running, frozen or legacy) | O/R ⚠ refuses (not running, frozen or legacy); jurors come from the jurors form | O/A | O/R ⚠ | O/R ⚠ | O/R ⚠ (to verify) |
| Statistics: stopped juror's "Start with a fair share" | O/R ⚠ | — | O/A | O/R ⚠ | O/R ⚠ | — |
| Statistics: Reassign files… | — | — | O/A if ≥ needed receivers | — | — | — |
| Conflicts: Stop the round / disqualify | — | O/A (disqualify) | O/A (stop) | O/R | O/A | ? |
| Edit round: check images | O/A | O/A | O/A | O/A | O/A | O/A |

### 1.2 Other roles

| Role | What they get on these pages |
|---|---|
| Organizer | Statistics (voting state per juror, no Start/Stop, no Add/Reassign; the waiting files line), the review page (no Distribute, no Edit), conflicts (view), authors. No Rounds list (`ADMIN_ROLES`) and no Edit round. |
| Juror | Their own galleries of open rounds; may stop, reopen or close their own round where allowed (logged on the round's statistics, 26.11.15). None of the management pages. |
| Series admin | As admin in the series' contests whose year is in reach; the add/reassign permission is `rolePermission(ADMIN_ROLES, derived = true)` then `administersRound`, which includes derived roles: consistent. `SeriesReachGuardSpec` lists the opted-in actions. |

### 1.3 Data conditions

| Condition | What is offered | Allowed? |
|---|---|---|
| 0 jurors in the contest, round in review | Distribute (rounds list, review page) → jurors form with nobody and a link to Users | refused with a message (`NoJurorsKey`) ✓ |
| 1 juror in the round, N = 0 or 1 | no Reassign ✓; Add juror ✓ | ✓ |
| 1 active + stopped jurors | Reassign if N > 0 and enough stopped; not for N = 0 ✓ | ✓ |
| all contest jurors already in the round | Add juror… still linked ⚠ | page with nobody to add |
| votes / no votes | criteria lock ✓; re-apply only without votes ✓; rating type locked either way ⚠ #184 | |
| previous rounds with a running add/reassign | new round form offered, Save refused (`error.previous.round.rebalancing`) ⚠ wait as error | |
| a job running on the round | Distribute hidden ✓; Start/Stop of jurors refused after the press ⚠ (button shown) | |
| a round left out that is being distributed | Save refused (`error.exclude.rounds.busy`) ⚠ wait as error; the select still offers it | |

## 2. Other inconsistencies found

Offered but refused:
1. **Round Start on a round in review** (rounds list): the button is shown; `setActiveGuarded`
   refuses (`distributing`), and the flash says "It starts by itself once its distribution has
   finished", which is false for a round in review (it waits for Distribute). The same message is
   used for a retired contest's round. Next step should be offered instead of Start: "Distribute
   to jurors".
2. **Add juror… and "Start with a fair share" on a round that isn't running** (statistics),
   while Reassign is hidden there: the same page gates two juror actions differently.
3. **Juror Start/Stop while a job holds the round**: shown, refused after the press with
   "busy"; the job is visible on the rounds list, not on the statistics.
4. **"Leave out files in round(s)" offers a round being distributed**, then the save is refused;
   same for a previous round with a running add/reassign. Both are waits, presented as errors on
   a full form re-render.
5. **Every refused save comes back as a different form** than the one sent (§0.5): the
   `formWithErrors` branch bypasses `roundFormView`.

Allowed but not offered:
6. **Distribute from Edit round** (#186): allowed (the round is in review), offered only via
   the review page or the rounds list.
7. **Rating type in review** (#184): nothing depends on it before distribution, yet it is locked.
8. **The review link on Edit round** is shown for every existing round (`editRound.scala.html`,
   comment says "a round waiting for review"): harmless (the review page works for any round),
   but the button reads as a next step where there is none.

Silent:
9. **400s are not logged** with their reason (§0.5), so production shows the symptom only.
10. **Only the first global form error is shown** (`snippets.formError`, `headOption`).

## 3. Candidate practices

| | Practice | Cost | Example in this repo |
|---|---|---|---|
| a | **Action-availability matrix:** one function per surface, e.g. `RoundActions.of(round, user, ctx): Map[Action, Availability]` with `Availability = Offered | Refused(messageKey) | Hidden`, used by the views (`@if(actions(Reassign).offered)`) and by the controllers before their own lock-held check; plus a table-driven spec over state × role × condition asserting *offered ⇒ allowed* and *allowed ⇒ offered (or listed as an intentional `—/A`)* | Medium: 1–2 PRs to introduce for rounds and jurors (≈ 12 actions), then each new action adds a row. The lock-held refusals stay where they are; the matrix is the cheap, unlocked pre-check they already duplicate (`RoundRebalanceHandler.isRunning` is that pre-check for Reassign today) | `RoundReassign.reassignableJurors` + `reassignRefusal` are already one rule used by the page, the POST and the job: generalise that |
| b | **Reachability invariant:** for each non-final state (draft, review, active, frozen, stopped), every page that shows the round (rounds list, edit, statistics, review, conflicts) offers at least one next step, or names where it is | Low once (a) exists: a spec iterating the matrix; without (a), HTML scraping per page | #186 is exactly this failure: review state, Edit page, no next step |
| c | **Playwright journeys** per user story: create → review → re-apply → distribute → vote → add juror → reassign → stop → next round, with dead-end detection (on each page, the next step's control must exist and its POST must not be refused) | High: each journey ≈ 1–3 min per engine; flaky risk; needs its own contest (as `review.spec.js` does) | `browser-test/tests/review.spec.js`, `organizer.spec.js`; story IDs A-01…A-30 in `docs/testing/stories.md` (A-04 and A-21 are "Part") |
| d | **Refusal UX checks:** every 400/refusal renders the message next to the control that caused it, the form comes back in the same mode (re-apply, new, edit) with the same menus, and the reason is logged once at INFO/WARN with a stable key | Low–medium: one helper for the round form's re-render, one controller spec per refusal kind asserting mode + field position; a log line in `saveRound` | `RoundEditPageSpec:511` checks one aspect (unrated round's form shown again); `Secured.denialLogLine` is the model for a key-value refusal log |
| e | **Review checklist item:** "new or changed action → add it to the matrix (a) and to both specs (a, b)" in `docs/testing/review-checklist.md` | Very low | the checklist already has "Concurrency and guards" |
| f | **Tripwire guard** (as `SeriesReachGuardSpec`, `RouteAuthSpec`): a source scan that every `routes.RoundJurorsController.*`, `RoundReviewController.distribute`/`jurors`, `RoundController.setRound`/`setRoundUser` link in `app/views` sits inside a matrix check, with an allowlist | Low; must have its self-test (practices §4.2) | `RouteAuthSpec` "be classified by the scan as built (it is not vacuous)" |

## 4. Recommendation and rollout

**Recommendation:** (d) and (e) now, (a) + (b) + (f) for round and juror actions next, (c) only
for the two longest journeys.

Order, fitting the queue:
1. **26.11.17 small fixes** (with #184 and #186): one PR per topic.
   - #186: Edit round of a round in review gets "Distribute to jurors →" (the jurors form) beside
     Re-apply; spec: the review state offers it on Edit, the rounds list and the review page.
   - #184: rating type editable in review (no votes, no job, no later round using it).
   - A separate **refusal UX** PR (d) for the round form, with the six fix candidates of §0.5:
     sections with an error open and a linked error summary; the error branch through
     `roundFormView`; the `form_refused` / `resubmit_dropped` lines of #191 (slice M-1); the form
     token checked before validation, and the create form's history entry replaced by a URL that
     resolves the token ([user], §5 Q6); up-front input checks. Specs: a field error in Media
     filtering comes back with the card open and in the summary; a refused re-apply comes back in
     re-apply mode with editable filters; a new round's refused form keeps its regions; a POST of
     a token that already saved a round redirects to its Edit page with "Round N is saved. You
     can change its settings here.", not a 400. **Playwright check:** save a new round, press
     Back, and land on that round's Edit page with the line, not the filled new-round form. The
     mechanism is **to verify in Chrome, Firefox and Safari** (bfcache, `no-store`). This
     addresses the 11 × 400 of 2026-11-25.
     **Setup in numbered steps (§0 rows 6–7), [user] decided 2026-11-25, same PR:**
     - A new round is **set up in numbered steps**, with a step bar on every setup page:
       1 "Which files" (the settings form) → 2 "Check the matching files" (the review page) →
       3 "Jurors" (choose them, then "Distribute to jurors") → open to jurors. After the form is
       saved the round has no jurors, so it is not "created" or "ready": no page calls it that
       before it opens to jurors.
     - The form has **one button, "Save and find matching files"**, with the help line "The app
       picks the contest's files that match these settings, for you to check. This can take a few
       minutes." (rejected: "select files", unclear; "gather"/"collect", which sound like fetching
       from elsewhere: the files are already in the contest's list, the app picks out the matching
       ones). It replaces "Choose jurors next" and "Review images first".
     - After saving: "Round 4 is saved. Step 2 of 3: its matching files are being found. Check
       them, then choose jurors."
     - **Step 2** (review page) heading shows "N of the contest's M files match"; "Change the
       settings" goes back to step 1 (Edit round, re-apply while in review). It states plainly:
       "Jurors can't see this round yet. When the files look right, choose jurors and distribute
       to them."; once the matching has finished, the step-3 action is its primary (visually main)
       one; while it runs, or if no file matches, that action is shown disabled with its reason.
     - **Step 3** (jurors page) heading names the round and the step (e.g. "Round 4 – step 3 of 3:
       jurors"), so it can't be mistaken for the submitted form; its button stays "Distribute to
       jurors" (accurate: each juror gets a share).
     - The step bar and the round's current step also show on Edit round of a round being set up
       (this covers #186), and Back after saving lands there (Q6).
     - On the Rounds list a round being set up reads "Setting up – step 2 of 3: check files" or
       "Setting up – step 3 of 3: choose jurors", with the step's action in place of Start (Q4).
       PR #192 ships interim wording ("Not started – waiting for distribution"); this replaces it.
     - Round statistics and the other pages that show a round being set up carry the same one-line
       state and a link to its current step (§0 row 7).
     - Labels say what happens to the user's round, not app concepts. The ru, uk and fr wording is
       settled in translation review at build time.
     - An edit save of a round already open says it was saved ("Round N (description) is saved.")
       on the rounds list.
     - Specs: the after-save line and the step bar on step 2, the disabled step-3 action with its
       reason while matching, the Rounds list's step states, and the edit save's confirmation.
     - The build includes screenshots of steps 1–3 at 1366 and 390 px wide, for review.
   - Smallest wins from §2: on a round in review the Rounds list hides Start and shows "Distribute
     to jurors" in its place; a correct message for a retired contest's round; Add juror… gated
     like Reassign.
2. **Lineage slice A** (being built: track label, chip, filter) adds no round action; it should
   not wait. Its new chip/filter links are views only. The checklist item (e) applies to it from
   its review on.
3. **`RoundActions` matrix (a) + reachability spec (b) + tripwire (f)**, one PR, after 26.11.17:
   rounds list, Edit round, statistics, review, jurors form, conflicts. It moves the conditions
   that exist today, unchanged except the §2 fixes already merged, so its diff is a refactor
   plus specs.
4. **Regional winners report**: read-only, no round actions; unaffected. If it adds a link from
   the rounds list, it goes through the matrix.
5. **Playwright journeys (c)**: two journeys, "create → review → re-apply with an error → distribute
   → vote → stop → next round" and "running round: add juror → reassign → stop juror", with a
   generic dead-end check, after (3), so their checks can read the same expected matrix.

**Problem monitoring** (refused saves, dead ends and the like recorded in the app and shown to
root and admins, rather than found in the access log) is its own plan, draft PR #191; this plan
uses its `form_refused` and `resubmit_dropped` lines (slice M-1). A candidate pattern for it:
"a round in review for more than N hours after its selection finished", shown on its Problems
page (§0 row 7).

## 5. Questions for the maintainer

1. **Adopt the action matrix (`RoundActions`) for round and juror actions?** It centralises the
   show/allow decision for ≈ 12 actions; lock-held refusals stay. **[user] Decided 2026-11-25:**
   yes, after 26.11.17, as one refactor PR with its specs (§4 step 3).
2. **What should a refused round-form save look like?** Options: the same form in the same mode
   with the error next to its field (needs field-keyed errors for the form-level checks), or the
   same form with all errors listed at the top. Recommendation: same mode, all errors at the top
   now (26.11.17), field placement as part of (a).
3. **Log refused saves?** A line per refused round save with the message keys (no input text):
   `form_refused`, and `resubmit_dropped`, as the problem-monitoring plan (#191, slice M-1)
   defines them. Recommendation: yes, in the 26.11.17 refusal PR; it is what would have explained
   the 11 × 400.
4. **Round in review on the rounds list: hide Start and show "Distribute to jurors" in its place?**
   **[user] Decided 2026-11-25** (PR #192): no Start on rounds not yet distributed, truthful
   refusal messages, one Distribute action per row as its primary button. The step wording of §4
   replaces #192's interim state text later.
5. **Gate "Add juror…" and "Start with a fair share" like Reassign (running rounds only)**, or keep
   them offered with the page explaining? Recommendation: gate them, with a one-line hint
   "Start the round to add jurors" on a stopped round, and none in review (the jurors form does it).
6. **Back after saving a new round.** **[user] Decided 2026-11-25:** the stale create form is never
   shown again. On submit (or creation) the form page's history entry is replaced by a URL that
   resolves the form token: a token that already created a round redirects to that round's Edit
   page with one line, "Round N is saved. You can change its settings here."; a token whose
   submit was refused shows the form as usual. No duplicate can be made this way; another round is
   started with the usual "Add round" button. The server checks the token before validating
   (today it validates first). Rejected: a stale form with two links, and the label "New round
   form" (it names app logic, not what happened to the user's round): user-facing text says what
   happened to their round. To verify in Chrome, Firefox and Safari (bfcache, `no-store`), with a
   Playwright check (§4 step 1).
7. **Waits shown as errors** (`exclude.rounds.busy`, `previous.round.rebalancing`): keep refusing,
   or accept the save and let the job wait? Recommendation: keep refusing for 26.11.17, but say
   "wait for job X (link), then save again" and keep the form; decide queuing later.
8. **Playwright journeys: how many, which engines?** Recommendation: two journeys (§4.5), Chromium
   only in CI, the others on release.
9. **Add the checklist item (e) now?** Recommendation: yes; it costs one line and applies to
   lineage slice A's review.
10. *(Superseded 2026-11-25 by the decided setup steps, §4 step 1: one button, "Save and find
    matching files".)*
