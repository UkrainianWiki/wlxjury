# Roles and the prejury workflow: WLX Jury compared with Montage

Date: 2026-11-10. This is a read-only research note for the decision "several roles per person in one
contest". It was written against v1 of the multi-role plan (superseded, not kept): "plan §N", "multi-role-plan
§N" and "plan U19" below are v1's sections and site ids (v1's U19, the picker's role labels, is v2's U21). A bare §N is this
note's own section. The decisions taken since are in `docs/plans/2026-11-10-multi-role-plan.md`
(Design S, "Juror stays alone", pre-screening rounds).

- **WLX Jury** at `c9805b31` (worktree `C:/scala/wlxjury-followups`, branch `ukwikidev/followups`,
  "Mirror of stack/96-organizer-docs d3aefd50 (PR #279)", 2026-11-10). Paths below that start with `W:` are
  relative to this tree.
- **Montage** (hatnote/montage) at `bfb550b6ba38` on the default branch `master`, committed
  2026-11-02 13:05:47 +0100 ("Localisation updates from translatewiki.net"). It was fetched today. The clone's
  working tree (`C:/python/montage`, after the maintainer's `git pull`) is at the same commit. Paths that start with
  `M:` are at this commit. The most recently active branches (`beta/2026-11-integration`, `fix/*`, 2026-10-31 to
  11-02) change nothing about roles.
- The web pages were read today: Commons:Montage revision 1183665424 (2026-03-19,
  https://commons.wikimedia.org/w/index.php?title=Commons:Montage&oldid=1183665424), and the GitHub
  issues and PRs cited by number (https://github.com/hatnote/montage/issues/N).
- The earlier comparison (local notes `docs/comparison/02-montage.md`, `03-comparison.md`, not in the repo;
  Montage f9ddfbd) agrees on the
  role model. Everything below was checked again at bfb550b. The line numbers have moved since that comparison.

"Inferred" marks a conclusion that the code does not state directly.

**Bottom line.**
- Montage gets several roles per person from scope separation (global, campaign, round). It gives no input on S vs A.
- On confidentiality Montage is the counterexample: coordinator-jurors see everything mid-round. Adopt plan §3.3.
- R′ (vote by vote rows in the round, i.e. round assignment, whatever the access role) is a real option. Its true costs are in §4.1.
- The juror picker's default matters. WLX pre-ticks every contest juror, so under S Organizer+Juror prejurors are pre-ticked into later rounds (§4.5).
- Scoped admins belong in a grant table, not in membership rows (§4.3).

---

## 1. The role models side by side

### 1.1 Montage

Montage has four roles. Each is stored at its own scope, in its own place.

| Role | Stored in | Scope | Granted by | May do |
|---|---|---|---|---|
| **Maintainer** | Hard-coded list `MAINTAINERS` (`M:montage/rdb.py:145-148`); `User.is_maintainer` checks it (`:186-188`) | Global | A code change and deploy | Bypasses every campaign and round check (`UserDAO.get_campaign`/`get_round`, `rdb.py:1091-1093, 1120-1122`; also the juror side, `JurorDAO.get_campaign`/`get_round`, `:2869-2871, 2881-2883`). Adds and removes organizers (`MaintainerDAO`, `:2755-2805`; route `M:montage/admin_endpoints.py:43-44, 1060-1075`). Reads the global audit log and the active users list. "Intended to only add organizers" (`M:design.md:126-127`). |
| **Organizer** | Global flag `users.is_organizer` (`rdb.py:164`) | Global, but only for creating campaigns | A maintainer (`rdb.py:2788-2797`) | Creates series and campaigns (`OrganizerDAO`, `rdb.py:2647-2716`) and lists all organizers and coordinators (`:2730-2752`). Manages **only the campaigns it coordinates**: `get_campaign` checks `campaign_coords` (`:1090-1101`), and the create endpoint always adds the creator as a coordinator (`M:montage/admin_endpoints.py:266`, "Organizer is included as a coordinator by default"). No one can cancel a campaign through the web app at bfb550b (note 1). |
| **Coordinator** | Table `campaign_coords(user_id, campaign_id)` (`rdb.py:287-299`) | Campaign | An organizer when creating the campaign, or **any coordinator** of the campaign afterwards (`CoordinatorDAO.add_coordinator`, `rdb.py:2262-2274`; routes `admin_endpoints.py:55-58, 1021-1057`) | Manages the campaign's rounds, jurors, quorum, imports, disqualification, results, report and audit log (`CoordinatorDAO`, `rdb.py:1196-2645`; the `/admin/campaign/*` and `/admin/round/*` routes, `admin_endpoints.py:50-96`). It can't cancel the campaign (note 1). |
| **Juror** | Table `round_jurors(user_id, round_id, is_active, flags)` (`rdb.py:494-500`) | Round | A coordinator, who names any Wikimedia username at round creation or edit (`create_round`, `rdb.py:1357-1365`; `modify_jurors`, `:2290+`). The picker searches all Wikimedia accounts (`globalallusers`, `M:frontend/src/components/UserList.vue:41-44`). No campaign membership is needed. | Votes on their own tasks only (`apply_rating`/`edit_rating` refuse another user's vote, `rdb.py:3198-3202, 3215-3217`) and sees their own progress. An optional per-round "Show own statistics" shows the juror only their own vote counts (`rdb.py:3030-3036`; `M:frontend/src/i18n/en.json:71-72`). |

Notes:
- Note 1, cancelling a campaign.
  - `OrganizerDAO.cancel_campaign` (`rdb.py:2717-2728`) needs an organizer or a maintainer (`:2649-2651`). Nothing
    in the web app calls it.
  - Its only caller is the server-side CLI (`M:tools/admin.py:252-260`), which passes a Campaign object where the
    method expects an id. PR #679 says it fixes this.
  - The only route (`admin_endpoints.py:52`, handler `:532-535`) calls a `CoordinatorDAO.cancel_campaign()` that
    doesn't exist, so it always returns a 500 (#675).
  - The frontend has no cancel-campaign call (`M:frontend/src/services/adminService.js` has only `cancelRound`,
    `:45`).
  - Open PR #679 would let coordinators cancel campaigns.
- The intended model says "Organizers add coordinators to campaigns they created" (`M:design.md:112-115`; Commons:Montage
  "More roles"). The code lets any coordinator add or remove coordinators. This was restored on purpose in 2017
  (issue #104, slaporte: "I will give this back to coordinators"). As a result a coordinator can remove the
  campaign's creator, who then loses the campaign (open issue #222, opened by CiellB, 2022-08).
- Issue #39 ("Standardize permissions", open since 2017) asks for a strict hierarchy, maintainer ⊃ organizer ⊃
  coordinator ⊃ juror, and notes "a few places where maintainers do not have full coordinator permissions".
- The UI label `montage-add-organizer` = "Add campaign organizer" (`en.json:174`) calls the global,
  maintainer-only `admin/add_organizer` (`M:frontend/src/services/adminService.js:16`; it is shown only when
  `is_maintainer` is set, `M:frontend/src/components/Campaign/ActiveCampaign.vue:13-18`). The label says
  "campaign", but the flag is global. The juror-side message "This round is not active. Please contact the
  organizer." (`en.json:137`) uses "organizer" loosely for whoever runs the campaign.
- The audit log records **the role an action was taken in** as its own column. `log_action` uses the DAO class
  name by default, and a call can override it with `role=` (`rdb.py:1172-1186`, default at `:1178-1179`). It does
  not use the `role` property (`:1069-1073`). For example, `add_coordinator` is logged as `role='organizer'`
  (`:2272-2273`).

### 1.2 WLX Jury

| Role | Stored in | Scope | Granted by | May do |
|---|---|---|---|---|
| **Root** | `users.is_root` (V61, `W:conf/db/migration/default/V61__Add_is_root_and_user_contest.sql:6-8`) | Account (every contest, no membership needed) | Root (`W:app/controllers/UserController.scala:76, 826, 845`) | Everything an admin can do, in every contest. Creates contests, which only root may do (`W:app/controllers/ContestController.scala:43, 62, 84`). |
| **Admin** | `user_contest.role = 'admin'` (V61 `:20-28`; one row per user and contest, PK `(user_id, contest_id)`) | Contest | An admin of the contest, or root (`UserController.scala:46, 652, 661, 703, 740`) | Manages the contest's people, rounds and files (`RoundController.scala:40, 72`; `W:docs/user/admin-guide.md:41-45`) |
| **Organizer** | `user_contest.role = 'organizer'` | Contest | An admin | **Read-only monitor**: round statistics, all jurors' votes, file list, CSV, contest files, conflicts page, comments. Does not vote (`W:app/db/scalikejdbc/User.scala:116-142`; `W:docs/user/organizer-guide.md:3-8, 21-27`). |
| **Jury** ("Juror") | `user_contest.role = 'jury'`, **plus** a `round_user(user_id, round_id, role, active)` row per round (`W:conf/db/migration/default/V37__Round_User.sql:1-14`) | Contest role and round assignment | An admin: the contest role on the users page, the round assignment in the round form, whose candidates are the contest's jurors (`RoundController.scala:100, 132, 286`; `W:app/services/RoundService.scala:68`) | Votes on their own vote rows in rounds of their contest (`User.mayVoteIn`, `User.scala:147-148`; `Secured.VotePermission`, `W:app/controllers/Secured.scala:320-324`). A paused `round_user` row stops new files and votes (`W:app/db/scalikejdbc/Round.scala:647-653`). |
| **Board / Audit committee / Staff** | `user_contest.role` (`User.scala:183-186`) | Contest | An admin | Nothing in the tool. They exist only so that the person's files count as conflicts of interest (`NON_ACCESS_ROLES`, `INACTIVE_ROLES`, `COI_ROLES`, `User.scala:178-194`) |
| **No** | `user_contest.role = 'no'` | Contest | An admin | A member without access (`User.scala:176`) |

`rounds.roles` (V3, `W:conf/db/migration/default/V3__Create_table_rounds.sql:9`, default `'jury'`; `Round.scala:28`)
was meant to say which contest role a round's jurors come from (`Round.availableJurors`, `Round.scala:83-84`).
The form posts a hidden `jury` (`W:app/views/editRound.scala.html:65`), and `availableJurors` has no caller
(multi-role-plan §0).

### 1.3 Term to term

| Montage | Nearest WLX Jury term | Match | Watch out for |
|---|---|---|---|
| Maintainer | Root | ≈ | Root is a flag in the DB that root can grant. A maintainer is hard-coded in the source. Root can act in every contest and alone creates contests. A maintainer is meant only to add organizers. |
| Organizer | No equivalent. Root's "create contest" right is the nearest. | ≠ | **False friend.** Montage's organizer is the global right to create campaigns. WLX's Organizer is a per-contest, read-only monitor. |
| Coordinator | Admin (per contest) | ≈ | In Montage any coordinator adds or removes coordinators. In WLX an admin manages all of the contest's people. An admin of one contest can't edit the details of an account that is also in other contests, but may still change that person's role in their own contest (`admin-guide.md:128-130`). |
| Juror | Jury role **and** a `round_user` row | ≈ | Montage has no contest-level juror: the round row is the whole grant. WLX needs the contest role before an admin can pick someone for a round. |
| (none) | Organizer (read-only monitor) | WJ only | Montage has no read-only observer: every coordinator can write (also `03-comparison.md:83`). |
| (none) | Board / Audit committee / Staff / No | WJ only | Montage's conflict-of-interest groups are the role groups only (§2.3). It records no "member without access". |
| Series | None. The contest type (WLM/WLE) is the closest. | – | A Series groups campaigns (`rdb.py:209-229`) but carries **no permissions**. Montage has no series-scoped role. |

---

## 2. Can one person hold several roles in Montage?

### 2.1 Yes, because each role lives at a different scope

The four roles are four independent facts about a person: a source list, a user flag, campaign rows and round rows.
Nothing in the code refuses a combination:

- **Coordinator and juror of a round in their own campaign.** `create_round` and `modify_jurors` accept any
  username (`rdb.py:1364`, `:2301`) and do not check whether that user coordinates the campaign. The home page asks
  for both lists and shows **"Juror campaigns"** and **"Coordinator campaigns"** as two sections
  (`M:frontend/src/components/Campaign/AllCampaign.vue:12-24`, fetched together `:56-114`; strings `en.json:23-24`).
- **Organizer and coordinator.** This is the normal case: the creator of a campaign is always added as its
  coordinator (`admin_endpoints.py:266`).
- **Maintainer and juror.** A maintainer can open the juror view of any round (`JurorDAO.get_round`,
  `rdb.py:2881-2883`), but can vote only on tasks assigned to them (`rdb.py:3198-3202`).

At any single scope a role is a yes or a no: a user is a coordinator of a campaign or isn't, and a juror of a round
or isn't. Montage never needs a set of roles in one row, because no two of its roles share a scope. This is
inferred from the schema in §1.1.

### 2.2 A coordinator who is also a juror sees everything, and no rule stops it

Access to a round's coordinator pages depends only on `campaign_coords` (`UserDAO.get_round`, `rdb.py:1120-1131`;
`CoordinatorDAO.from_round`, `:1224-1228`). No coordinator path checks whether the acting user is one of the round's
jurors. This is inferred from reading `UserDAO` and `CoordinatorDAO`: neither reads `round_jurors` for
`self.user`. So while the round is open, a coordinator who also votes in it can see:

- **in-progress results**: averages, damped averages and thresholds (`get_round_results_preview`,
  `admin_endpoints.py:664-688`). The comment at `:688` says rating rounds let you "take a peek at in-progress
  results". Issue #669 (open, 2026-10-31) reports that the ranking preview also opens before all ballots are in.
- **every juror's vote on every file**: `make_vote_table` keys each vote by juror username (`rdb.py:1939-1961`).
  The JSON `GET /admin/round/<id>/results` returns that whole table without checking the round's status
  (`get_results`, `admin_endpoints.py:888-892`). The results CSV has a "TODO: Confirm round is finalized"
  (`admin_endpoints.py:895-901`).
- each juror's progress (`juror_details`, `rdb.py:483`), the reviews and the flags (`admin_endpoints.py:80, 95`).

I found no issue or PR about a coordinator-juror's view of the round. I searched hatnote/montage issues and PRs for
"coordinator juror", "own votes", "conflict of interest", "role" and "permission". The nearest discussions:

- #115 (closed 2019): dschwen wrote that a juror's own statistics are "unproblematic" and that jury-wide
  statistics "could be an optional per round setting". Only own statistics were built (`show_stats`,
  `rdb.py:339-349, 3030-3036`).
- #74 (open since 2017) and #181 (closed 2021): see §3.3.

### 2.3 A coordinator-juror's own photos

The conflict-of-interest rule is disqualification by uploader, applied at import (`autodisqualify_by_uploader`,
`rdb.py:1608-1653`):

- The rule runs at import if any of `dq_by_uploader`, `dq_coords`, `dq_organizers` or `dq_maintainers` is set
  (`admin_endpoints.py:952-961`). Whenever it runs, the round's jurors are in the uploader list (`rdb.py:1611-1614`).
  So with the backend defaults (`dq_by_uploader: False`, `dq_coords: True`), jurors' photos are disqualified anyway.
- Coordinators (`dq_coords`), organizers (`dq_organizers`) and maintainers (`dq_maintainers`) are added by round
  config (`:1616-1634`).
- `dq_organizers` matches **every** `is_organizer` user in the tool, not only this campaign's (`:1622-1629`).
- The defaults disagree. The backend `DEFAULT_ROUND_CONFIG` has `dq_by_uploader: False` and
  `dq_coords`/`dq_organizers`/`dq_maintainers: True` (`rdb.py:68-79`). The new-round form defaults to
  `dq_by_uploader: true` and the other three `false` (`M:frontend/src/components/Round/RoundNew.vue:352-358`).
- Commons:Montage still says coordinator and organizer disqualification is "not currently working" (rev 1183665424,
  "Disqualify coordinators"). Issue #274 reported this and was closed by PR #285 (2025-05).

---

## 3. Prejury

### 3.1 What WLX Jury means by it

- **The workflow:** the contest's organizing team pre-screens all files in a first round (usually yes/no) before the
  real jury rates the remaining ones. The prejurors are organizers who should go on monitoring the contest
  (multi-role-plan §3.4).
- **The history in the code.** "prejury" was a free-text value in `users.roles`. V61's comment lists it:
  "role is what users.roles held (jury, organizer, admin, prejury, ...)" (`V61...sql:7`). A 2015 test created
  "prejury" users next to jurors and organizers. It asserted that a round with `roles = Set("jury")` lists only the
  `jury` users (commit 8028e0b3, `test/db/scalikejdbc/RoundSpec.scala`). Inferred: `rounds.roles` was designed so
  that a round could take its jurors from another role, such as a prejury round. That is design R, as originally
  intended.
- **Today** the exact role allowlist (V76) refuses "prejury" (`W:test/db/UserContestRoleMigrationSpec.scala:39`;
  `W:docs/history/security/2026-11-02-role-allowlist-data.sql:38-41`). A prejuror therefore needs either a second
  account with another email, or the role switched to Juror for the prejury round and back to Organizer afterwards
  (multi-role-plan §0; `Secured.scala:313-319`).

### 3.2 Montage's counterpart

Montage has **no prejury role, round kind or term**. `git grep -i "prejury|pre-jury|screening|reviewer"` at
bfb550b finds nothing anywhere, translations included. Its counterparts are two mechanisms:

1. **Automatic disqualification at import**, a technical screening. Per round it can disqualify by upload date
   outside the campaign window, by resolution, by file type, and by uploader (§2.3)
   (`rdb.py:68-79, 1452-1653`; Commons:Montage "Disqualify by resolutions / upload date / jurors / coordinators /
   organizers"). Each entry is stored with a reason and the user who disqualified it (`dq_reason`, `dq_user_id`),
   and can be previewed and requalified (`admin_endpoints.py:80-90`). Manual disqualification is in API routes
   (`:85-88`). The UI for it is open PR #396. A rework of disqualification is proposed in #668 (2026-10-31).
2. **A yes/no first round** whose jurors are whoever the coordinator names, possibly themselves. Commons:Montage:
   "The yes/no round is used by most coordinators for the **initial screening** of all images uploaded in their
   competition. Qualitative bad images ..., images that are outside of the Commons scope, and for instance clear
   copyvio can quickly be removed" (rev 1183665424, section on round types). The next round's form fills in the
   previous round's jurors (`RoundNew.vue:349`).

So in Montage a "prejury" is simply round 1, and a coordinator who screens is simply a juror of round 1 (§2.1),
with full coordinator visibility (§2.2).

### 3.3 Community terminology

| Term | Where | Meaning |
|---|---|---|
| **pre-jury** / **prejury** | Meta, "Learning patterns/The pre-jury as a tool for community engagement" (Claudia Garad, WMAT, 2014): https://meta.wikimedia.org/wiki/Learning_patterns/The_pre-jury_as_a_tool_for_community_engagement | A broad pre-jury, "basically all community members who want to participate", filters submissions "in regard of general quality and/or relevance" to reduce the main jury's work. It advises that "creators of the pictures are not known during the assessment" and that every picture gets at least two reviews. |
| **Prejury** (tab and tool) | https://commons.wikimedia.org/wiki/Commons:Wiki_Loves_Earth_2022_in_Australia/Prejury | Wikimedians "rate all competition entries (except your own)" 1-5. "Four stars: ... The main jury should look at it." |
| **Vorjury** | Commons "WikiDaheim Vorjury 2017" (search hit). WLM 2018 winners page: "selected by the pre-jury of WikiDaheim" (https://commons.wikimedia.org/wiki/Commons:Wiki_Loves_Monuments_2018_winners) | The Austrian and German usage, with its own tool |
| **reviewers** vs **judges** | Montage #74 (MichaelNMaggs, WLM UK, 2017) | Reviewers "cut down the numbers". Judges decide. "We can't afford to disqualify reviewer submissions". #74 asks instead "to ensure that a juror is never presented with their own images for review". Montage still offers only disqualification. In #181 (2020-11-22) mahmoud called the missing feature "assign around": "an option to automatically disqualify photos by judges ... but not one to assign around them". |
| **initial screening** | Commons:Montage (above) | Montage's yes/no first round |
| **disqualification** | Montage code and UI | Rule-based or manual removal, with a reason |

---

## 4. Lessons for the decision

### 4.1 Which design Montage resembles

- **Juror: R taken further.** In Montage the round row is the whole voting grant: there is no contest-level juror
  role (`round_jurors`, `rdb.py:494-500`). WLX already has the same row (`round_user`) but adds a contest role on
  top of it.
- **Coordinator: neither S nor A.** It is one membership fact per campaign, with no set of roles in it.
- **Montage has no counterpart to S, S-join or A**, because it never stores two roles at one scope. Its "several
  roles" come from **scope separation**: global, campaign, round.
- One variant the plan doesn't list, inferred from Montage. Call it **R′**: a member with an access role (jury,
  organizer or admin) may vote in a round if they have vote rows in it and are not stopped there, **whatever the
  access role**. Being "not stopped" means no inactive `round_user` row (`RoundUser.isStopped`,
  `Round.scala:647-653`; a missing row is not stopped). R′ is defined on vote rows because legacy rounds have no
  `round_user` rows: `User.distributionJurors` falls back to vote rows (`User.scala:582-584`). The admin grants
  the rows by ticking the person in the round form. The jury role stays the access role for people who only vote.
  - What R′ gives:
    - prejury needs no round kind and no new role;
    - admin+juror and organizer+juror exist only in rounds where an admin assigned them;
    - the contest role keeps meaning "what you may see and manage".
  - What only R′ costs:
    - It reverses #278's rule ("votes by the jury role only", multi-role-plan §6 item 1). The admin's mental model
      flips: switching someone away from Juror no longer stops their voting, only pausing their `round_user` row
      does. The 26.11 workaround (Juror during the prejury round, then back to Organizer) and the
      `MutationAuthorizationSpec` case "refuse every vote of a juror made an organizer"
      (`W:test/controllers/MutationAuthorizationSpec.scala:327-329`) flip with it.
    - Round creation drops ticked organizers and admins silently: `RoundService.submit` →
      `User.loadJurors(round.contestId, jurorIds)` filters on `JURY_ROLE` (`RoundService.scala:68`,
      `User.scala:895-896`). The form's candidate lists need the same change (`RoundController.scala:100, 132, 286`).
    - The juror picker would list organizers and admins, so role labels matter (§4.5).
    - It still doesn't record "juror + board" (that needs S or the `coi_role` column).
  - Costs R′ shares with S (new work in both designs):
    - The start page sends any organizer to the statistics page before checking for jury rounds
      (`W:app/controllers/LoginResults.scala:20-21`). An Organizer+Juror under S hits `ORG_COM_ROLES` first as well,
      and plan §6 item 6 lists the fix as an S follow-up.
    - The plan §3.3 confidentiality predicate is new code in both designs. R′ just leaves out its
      `user.hasRole(JURY_ROLE)` conjunct.
    - The 5 vote-control view sites test `roles.contains("jury")` (multi-role-plan §0). Plan §6 moves them to
      `user.mayVoteIn(round)` under S too, and under R′ that check changes.
  - Migration: S needs V97. R′ needs none for prejury.
  - **No new query is needed.** The vote paths already call `RoundUser.isStopped`
    (`W:app/controllers/GalleryController.scala:318`, `W:app/controllers/LargeViewController.scala:64`), and
    `VoteJdbc.rate` only touches the voter's own rows; a file that isn't theirs is a 404
    (`W:app/db/scalikejdbc/VoteJdbc.scala:197-200`).
  - R′ is presented as an option, not a recommendation.

### 4.2 Round vs contest scope (R vs S)

- Montage shows that a round-level voting grant is enough to run a screening round and a jury round with different
  people. This is the real-world case behind plan §3.4.
- But in WLX the contest role also drives the start page, which galleries a user sees
  (`GalleryService.isNotAuthorized`, `W:app/services/GalleryService.scala:37-56`), conflict-of-interest membership
  (`COI_ROLES`) and invitations. Montage has none of these (inferred: its juror pages derive everything from
  `round_jurors`, `JurorDAO.get_campaign`/`get_round`, `rdb.py:2867-2892`).
- So moving to pure round scope would mean more than a change to the vote check. S keeps these contest-level
  meanings. R/R′ only changes who votes.

### 4.3 Scoped "middle" admins

- Montage has a global overseer that no one can remove: the hard-coded maintainer. A maintainer bypasses the
  membership checks in `UserDAO.get_campaign`/`get_round` (`rdb.py:1091-1093, 1120-1122`). This is positive
  evidence for a grant that lives outside membership rows. What Montage lacks is a **middle tier**:
  - An organizer sees only the campaigns it coordinates (`rdb.py:1090-1117`).
  - leilaz, who set up campaigns by hand, wanted to be added to every campaign. In a comment on #13 (2017-06-11): "My own
    user-id should ideally be added to all campaign by default". #13 itself is slaporte's "Request to become
    coordinator". leilaz is not in today's `MAINTAINERS`, and lgelauff wrote on 2026-06-23 that #582 likely
    resolves #13.
  - Creators lose access when another coordinator removes them (#222).
- Lesson: an overseer's grant should **not** be a removable membership row in the contest. Montage's maintainer
  shows the working pattern at the global level. That supports plan §5: a separate `admin_scope` table
  (contest type or country) merged in at check time.
- Montage's Series would be the obvious scope, but it carries no permissions. A WLX contest type plus country can
  serve as the scope directly.
- Also, Montage's `dq_organizers` applies the global organizer group to every campaign (`rdb.py:1622-1629`). That
  over-broad conflict-of-interest rule is what the plan avoids by making a scoped admin a conflict-of-interest
  person only within its scope (plan §5, third `UNION` branch).

### 4.4 Confidentiality for organizer-jurors

- Montage is the cautionary example. A coordinator who votes can read in-progress averages and every juror's votes
  for the round they are voting in (§2.2). No rule and no open issue addresses it.
- WLX's plan §3.3 rule (in a round where you are a juror you see it as a juror until it closes) is stricter than
  Montage. By analogy (inferred), it is in the spirit of the community's pre-jury advice to keep assessments
  independent. That advice is strictly about hiding the creators of the pictures (Meta learning pattern), not about
  hiding other jurors' votes. Montage's issue #115 discussion likewise accepts own statistics as harmless and treats
  jury-wide statistics as opt-in.
- Recommendation (inferred from the above): adopt plan §3.3 as planned. Do not copy Montage's openness.

### 4.5 The round juror picker

- Montage's picker is open (any Wikimedia account) and fills in the previous round's jurors (`RoundNew.vue:349`).
- **WLX's new-round form pre-ticks every contest member with the jury role**: `User.loadJurors(contestId)`, whose ids
  go into `editRoundForm.fill` (`RoundController.scala:100-101`). It does not pre-tick the previous round's jurors.
- What that means for each design:
  - **Under S**, Organizer+Juror prejurors hold the jury role, so they are pre-ticked into Round 2 and every later
    round by default. Plan §3.4 step 5 treats a prejuror in Round 2 as an accidental tick. With this default it is
    the outcome unless the admin unticks them.
  - **Under R/R′**, prejurors are organizers without the jury role, so they are not pre-ticked. This is a point
    for R/R′, but only if the pre-ticked set stays the jury-role members while the candidate list widens to
    organizers and admins. Today one `loadJurors` call gives both (`RoundController.scala:100-101`), so R/R′ must
    split it.
- Recommendation under S: new rounds don't pre-tick members who also hold Organizer or Admin.
  - Pre-ticking the previous round's jurors instead does **not** work for prejury. Round 2's previous round is the
    prejury, so it would pre-tick only the prejurors and none of the real jury. Avoiding that needs a "prejury"
    marker on the round, which is R's round kind.
- In every design, add the role labels ("Ilya — Organizer, Juror", plan U19). Any design that lets organizers vote
  needs them.

### 4.6 Conflicts of interest for a broad prejury

- With `exclude_coi` on, WLX leaves out the files of every member with a COI role, jurors included (`COI_ROLES`,
  `User.scala:194`; `Round.scala:59`). I found no "don't show a juror their own files" option in the distribution
  code (searched `W:app/services/distribution/` for own-files handling).
- An organizing-team prejury adds no exclusion: organizers are COI members already (`COI_ROLES` includes
  `organizer`). An Austrian- or Australian-style community prejury (§3.3) would
  exclude many participants' photos from the whole round. UK's "reviewers" asked for exactly the alternative
  (Montage #74).
- Inferred: if broad prejuries are in scope, a round-level "don't assign a juror's own files" option is the missing
  piece in any design. It is a separate decision from the multi-role design.

### 4.7 Where Montage's choices don't fit WLX Jury

- **The read-only Organizer.** Montage has no observer, so it offers nothing for "organizer + juror" visibility.
  WLX must define it (plan §3.3).
- **COI-only roles** (board, auditor, staff) and "No": Montage stores no such groups. Conflict-of-interest
  membership is WLX's own concept.
- **Identity.** Montage jurors are bare Wikimedia usernames created on first mention (`get_or_create_user`,
  `rdb.py:1133-1154`), with no invitation. WLX has one account per email, invitations and share links. Grants
  there need consent and verification, so "just name them in the round" (R′) still needs an existing, verified
  member.
- **Session contest.** WLX checks run "in the current contest" (`Secured.inCurrentContest`/`inContest`,
  `Secured.scala:510-524`). Montage passes ids on every call. A set of roles per contest (S) fits WLX's model, and
  Montage's per-object rows fit Montage's.
- **The audit role.** Montage logs the role an action was taken in (§1.1). WLX's denial log records the required
  roles (`Secured.denialLogLine`). Under S, logging the role a user acted in (organizer or juror) would help review
  disputes. Inferred, optional.

---

## 5. Terminology recommendations (recommendations only)

1. **Keep "Organizer"** for the read-only monitor. It is already defined in both guides (`organizer-guide.md:3-8`,
   `admin-guide.md:41-45`), and it matches how WLM national teams talk about their organizing team. Add one help
   line wherever roles are picked: "Follows the voting. Doesn't change the contest or vote unless also a Juror."
   Mention in the admin guide that a Montage "organizer" is something else (the right to create campaigns).
2. **Don't adopt "Coordinator"** for Admin. 26.11 documents "Admin". A one-line glossary entry ("Admin ≈ Montage
   coordinator; Root ≈ Montage maintainer") helps people who come from Montage.
3. **Call the screening round "Prejury"** in the docs and in the example round name. It is the community's term
   (Meta learning pattern, WLE Australia, WikiDaheim "Vorjury"). Its members are "prejurors". If a round kind or
   flag is added (R), label it "Prejury (pre-selection by the organizing team)".
   - Avoid "screening" alone: Montage uses it for an ordinary jurors' yes/no round.
   - Avoid "pre-selection" alone: WLX's round filters already select files.
4. **Keep the contest role and the round assignment apart in wording.** The role is "Juror"; a person in a round
   is "a juror of this round". The round form's list should say "Jurors of this round", with role labels if
   organizers can be picked.
5. **Name the two role groups** as plan §4 does: "Role in WLX Jury" and "Conflict of interest". Montage's word
   "disqualify" fits WLX's admin action on a conflict of interest (`canViewRoundConflicts`, only admins
   disqualify). WLX already uses it.

---

## 6. Inputs for the multi-role decision (multi-role-plan §7)

| plan §7 | Input from Montage |
|---|---|
| 1 Design | Montage gets several roles per person from **scope separation** (global, campaign, round), never from a set of roles in one row. That is evidence for keeping contest roles and round assignment separate. It doesn't choose between S and A, since Montage has no counterpart to either. S remains the fit for WLX's contest-level meanings (start page, galleries, COI, invitations: §4.2). R′ (any access-role member with vote rows who is not stopped may vote; §4.1) is a Montage-like alternative. It covers prejury and any other organizer+juror or admin+juror assignment. Costs only R′ has: reversing #278's rule (its spec, the admin's mental model), widening `loadJurors` in submit and in the form's candidate lists, picker labels. Costs shared with S: the start page, the plan §3.3 predicate (R′ drops the role conjunct), the 5 vote-control sites. S needs the V97 migration; R′ needs none for prejury. Picker trade-off (§4.5): under S, Organizer+Juror prejurors are pre-ticked into every later round by default. Under R/R′ they are not, provided the pre-ticked set stays jury-role members while the candidate list widens. |
| 2 One SET column or two | No input: Montage stores no COI groups (§1.3). |
| 3 Confidentiality and admin-juror statistics | Montage has **no** rule, and its coordinator-jurors see in-progress averages and every juror's votes (§2.2). Treat it as the counterexample and adopt plan §3.3. Admin-jurors keeping the statistics page (counts only) is still stricter than Montage. |
| 4 Admin + Organizer together | No direct input. Montage has no observer role, so its coordinator implicitly includes monitoring. That is a weak analogy for "Admin includes Organizer". |
| 5 Import on existing members | No counterpart: Montage has no contest membership. |
| 6 Invitation acceptance adds roles | Montage's grants are additive by construction: a new coordinator row or round row never removes another role. Weak evidence for union (inferred): Montage has no invitations and no consent step. |
| 7 Timing | No input. |
| 8-9 Migration and production checks | No input. |
| Not in plan §7 | (a) Round juror picker: under S, don't pre-tick members who also hold Organizer or Admin (WLX pre-ticks every contest juror, `RoundController.scala:100-101`), and add role labels (§4.5, plan U19). (b) Scoped admins: a grant table, not membership rows, as Montage's maintainer already is at the global level (Montage #13, leilaz's comment; #222; §4.3). (c) A broad prejury needs jurors never to be shown their own files (#74; "assign around", mahmoud in #181) rather than exclusion (§4.6). |
