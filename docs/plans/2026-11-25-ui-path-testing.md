# UI-path testing: offered actions that match allowed actions

**Status:** draft, for the maintainer's decisions (§5). Written 2026-11-25 from the maintainer's
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

**What they have in common:** each page decides on its own whether to show an action (a Twirl
condition), and each controller or service decides on its own whether to allow it (a permission, a
refusal under the round's lock). The two are written separately, at different times, and nothing
checks that they agree. Most fixes so far added the missing condition to one view and a spec for
that one case (§0). This plan proposes one source of truth per action and a table-driven check.

## 0. Known cases on master (26.11.16)

| # | Case | Fixed by | Guard spec | Still open |
|---|---|---|---|---|
| 1 | "Add juror…" listed people already jurors of the round | `92835218` (#122, 26.11.15): the add page lists only the contest's jurors outside the round; a stopped juror is restarted from Round statistics ("Start with a fair share") | `test/controllers/RoundJurorsAuthorizationSpec.scala:148` ("offer only the contest's jurors outside the round, never an active or a stopped juror"); `test/services/distribution/RoundRebalanceJobSpec.scala:397` (an active juror refused on page and POST) | (a) "Add juror…" is linked for every round its admin sees (`roundStatTable.scala.html:164`, `mayPause` only), also stopped, in review and being created, where the page then refuses ("not running"); Reassign is gated by `running` (line 27). (b) When every contest juror is already in the round the link still shows and the page has nobody to offer (to verify on the page). |
| 2 | Reassign offered a juror's files to that same juror | `31db48be` (archive #368, 26.11.13): the receivers are the round's other jurors (`RoundRebalanceService.reassignPreview`: `others = j.jurors.filterNot(from)`; `reassignRefusal`: `to = receivers.filterNot(_ == from)`); add-juror donors likewise exclude the juror added (`RoundRebalanceService.preview`) | None that names it: `RoundReassignAuthorizationSpec.scala:102` checks the receivers shown, `:286` refuses other bad receivers, but no example posts `receiver = from` or asserts the juror is absent from the receiver list | (a) A posted `receiver = from` is silently dropped, not refused: harmless, but no spec pins either behaviour. (b) Add an explicit example on page and POST for both reassign (receiver) and add (donor). |
| 3 | "Reassign files…" offered when no other juror can take the files | `fc5f3ad3` (archive #383, hotfix 26.11.13.1): `RoundReassign.enoughReceivers` (N > 0: at least N other jurors, stopped ones count since a ticked one is restarted; N = 0: at least one other *active* juror); the page, the POST and the job's first step refuse with `AloneKey` | `RoundReassignAuthorizationSpec.scala:164` (alone with N = 1 and N = 0; the other juror with no active jury membership, deleted, or stopped with N = 0; N = 2 with one other) and `:193` (N = 1 with a stopped other, N = 2 with two: offered); `RoundReassignJobSpec.scala:479` (the job's own check) | **One active plus stopped ones:** covered (stopped count for N > 0, not for N = 0). **Stopped round_user row:** covered (`juror2 -> false`); a NULL `active` (before V108) reads as stopped, as everywhere. **Series-derived jurors:** do not exist: series grants derive admin and organizer roles only (`Secured.derivedRoleIn`); receivers are read from real `user_contest` jury rows (`User.contestMembers`), so nothing to cover. **Open:** a receiver whose account is in an account join (J-2) is not among the examples. |
| 4 | Criteria UI shown where criteria can't be edited | `3fdff615` (archive #355, 26.11.11): an existing yes/no or comments-only round's form has no criteria and no worst mark (`unratedRound`); `9362cfe5` (archive #386, 26.11.14, "A started round's criteria can no longer be changed"): a rated round running or with a vote lists its criteria read only (`lockedCriteria` → `RoundCriterion.started`), and `RoundCriterion.update` refuses a change | `RoundEditPageSpec.scala:476`, `:498`, `:511` (also on the form shown again with an error), `:528`; `RoundCriteriaSpec.scala:232`, `:406`, `:436`, `:464` (running, or frozen while active: locked; editable again once stopped before a vote), `:365` (re-apply) | **In review:** criteria editable (not started) but the rating type locked (`editRound.scala.html`: `rates` readonly by `noEdit = !newRound`): #184. **Paused by a job (frozen while active):** covered. **Re-apply:** criteria written in its transaction, refusal covered. **After a refused re-apply save:** the form comes back without re-apply mode (§0.5, cause A), so the filters turn read only and the next press is a plain Save. |
| 5 | 11 × 400 on `POST /admin/rounds/save` | not fixed | partial: the 400s are specced one by one (`RoundExcludeRoundsFormSpec`, `RoundWorstMarkFormSpec`, `RoundCriteriaSpec`, `RoundEditPageSpec:183`, `RoundReviewJurorsReapplyPageSpec:159`); none checks what the page offers *after* a 400 | see §0.5 |

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
global error is not shown at all. **No 400 of either kind is logged** (only `failed` logs, for
unexpected exceptions), so production's access log shows the 400s but not why.

**Likely causes of the 11 failures** (from the code; no production data read):
- **A. A refused edit of a round in review loses its re-apply mode.** The `formWithErrors` branch
  passes neither `reapply` nor `regions`, `specialNominations`, `coi`, `waiting`,
  `distributionJob`, `scoreRuleWarning` or `storedCriteria`, and gives the contest's jurors, not
  the round's. With `reapply = false` and `newRound = false`, `imageFiltering` renders every
  filter read only (the category boxes readonly, their values in hidden inputs). The invalid value
  is posted again on each Save, and can't be corrected on that page: a **loop of 400s** until the
  admin reopens Edit round from the list (a GET, which restores re-apply mode). This fits "many
  400s, then success" best. The same holds for any existing round whose *stored* filters no
  longer pass today's validation (a legacy category rule or title after #170's two boxes): every
  save of its edit form is refused, with read-only fields.
- **B. A wait shown as an error.** `error.exclude.rounds.busy` and
  `error.previous.round.rebalancing` refuse until another round's job finishes (minutes on a large
  round); the admin presses Save again until it passes. Eight minutes is a plausible job length.
- **C. Lost menus on a new round.** On a new round's refused form the region and special-nomination
  menus are missing (`regions = Map.empty`), so a resubmission silently drops those filters; not a
  400 by itself, but the form the admin fixes is not the one they filled in.
- **D. A global error far from its control**, e.g. the category rule or mixed previous-round types,
  read as "nothing happened".

These 400s are the action's own, so `ErrorHandler`'s `client_error … status=400` line is not written
for them; the access log's request lines and the round's state at the time would tell A from B
(§5 Q6).

## 1. Inventory: round and juror actions by state, role and condition

States as stored (`Round.lifecycle`, V105) with the flags that refine them: **draft** (being
created: `distributing`, its fill running or failed), **review** (`inReview`: paused, distributing,
filled), **active** (open to jurors), **frozen** (a job holds it: `freezeCount > 0`, active or not),
**stopped** (paused, not distributing), **cancelled** (creation cancelled). `finalized` is not
written yet. Roles: root, admin (real `user_contest` row), series admin (derived admin, opts in per
action: `derived = true`; all the actions below opt in, and `Secured.administersRound` reads the
derived role too), organizer (read only + comments), juror.

Cells: **O/A** offered and allowed; **O/R** offered, refused with a message; **—/A** allowed but not
offered on that page; **—** neither. ⚠ marks a mismatch (§2).

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
| Statistics: Add juror… | O/R ⚠ | O/R ⚠ (jurors come from the jurors form) | O/A | O/R ⚠ | O/R ⚠ | O/R ⚠ |
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
5. **Every refused save comes back as a different form** than the one sent (§0.5 A, C): the
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
   - A separate **refusal UX** PR (d) for the round form: the `formWithErrors` branch goes
     through `roundFormView` (keeps re-apply mode, menus, the round's jurors); all global errors
     shown; one WARN log line per refused save (`round_save_refused contestId roundId keys=…`).
     Spec: a refused re-apply comes back in re-apply mode with editable filters; a new round's
     refused form keeps its regions. This addresses cause A of the 11 × 400.
   - Smallest wins from §2: no Start button on a round in review (show "Distribute to jurors"),
     and a correct message for a retired contest's round; Add juror… gated like Reassign.
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

## 5. Questions for the maintainer

1. **Adopt the action matrix (`RoundActions`) for round and juror actions?** It centralises the
   show/allow decision for ≈ 12 actions; lock-held refusals stay. Recommendation: yes, after
   26.11.17, as one refactor PR with its specs.
2. **What should a refused round-form save look like?** Options: the same form in the same mode
   with the error next to its field (needs field-keyed errors for the form-level checks), or the
   same form with all errors listed at the top. Recommendation: same mode, all errors at the top
   now (26.11.17), field placement as part of (a).
3. **Log refused saves?** A WARN line per refused round save with the message keys (no input text),
   like `security.authz`'s denial line. Recommendation: yes, in the 26.11.17 refusal PR; it is
   what would have explained the 11 × 400.
4. **Round in review on the rounds list: replace Start with "Distribute to jurors"?** Start there
   can only be refused. Recommendation: hide Start for review, draft and cancelled rounds; the
   review line already has Distribute.
5. **Gate "Add juror…" and "Start with a fair share" like Reassign (running rounds only)**, or keep
   them offered with the page explaining? Recommendation: gate them, with a one-line hint
   "Start the round to add jurors" on a stopped round, and none in review (the jurors form does it).
6. **Look at production for the 11 × 400?** A read-only look at the access log lines and the
   round's state then would confirm cause A or B. Recommendation: yes, one read by the maintainer
   (the agents have no DB login); the fix in Q2/Q3 doesn't depend on it.
7. **Waits shown as errors** (`exclude.rounds.busy`, `previous.round.rebalancing`): keep refusing,
   or accept the save and let the job wait? Recommendation: keep refusing for 26.11.17, but say
   "wait for job X (link), then save again" and keep the form; decide queuing later.
8. **Playwright journeys: how many, which engines?** Recommendation: two journeys (§4.5), Chromium
   only in CI, the others on release.
9. **Add the checklist item (e) now?** Recommendation: yes; it costs one line and applies to
   lineage slice A's review.
