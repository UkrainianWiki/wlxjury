# Contest series: adminship across years, and splitting contests reused across years (plan, 2026-11-18)

**Status:** CY-1 built on `stack/209-series-cy1` (V109; 2026-11-20); CY-0, the reuse dry-run
report, built on `stack/212-series-cy0-report` (no migration; its production-copy numbers in §4.7);
CY-2, series admins, built on `feature/series-cy2-admins` (V112; 2026-11-24; without the series
admins' creation path and CA6's disabled grantees, which ship with CY-3, §3.2); the rest is plan. The first
batch, CA8 and the second batch are decided (2026-11-18). **Refined by the maintainer on
2026-11-20:** a national contest may host rounds of several other countries (R4, §4.1); CY-1's
inline reuse check reads every round's signals for it (§3.2). **Every decision is taken:** CA24 and,
after the CY-0 report's numbers, the third batch (CA9–CA13 and CA23) were decided on 2026-11-20, each
as recommended, so CY-4 will be built (§4.4, §7) with no open decision. Revised
after the final review of 348407ca. **CA20 reworked by the maintainer later on 2026-11-18:** one
start year per grant replaces the fixed window. **CA17 re-decided and CA18 dropped the same day:**
the start year is the only rule; the grant date no longer matters.
**Pilot (user testing) added by the maintainer, 2026-11-18:** a WLM UA admin pilots series
adminship after each PR, then the Nepal admin (§7, Pilot).

- **Reworked around an explicit Series entity** (the maintainer's CA1), replacing the earlier
  derived scopes.
- **Reviewed:** an independent review, a follow-up review, then a review of the series rework
  (a6ab6f82). The security rules those reviews set are kept here, re-expressed for series admins.

**Decided by the maintainer, 2026-11-20** (the third batch after the CY-0 report's numbers, §4.7,
and CA24; each as recommended):

- **CA9: the approach.** The report, then per contest root confirms: move the clean clusters into
  new contests of the right series and year, leave R3, or start clean. CY-4 will be built.
- **CA10: the source's file links are kept.**
- **CA11: memberships on a move.** The Juror role in the target for the moved jurors only, skipping
  deleted accounts, O14 conflicts and protected accounts, the rest listed; their memberships in the
  source stay.
- **CA12: the way back.** Undo (by `split_item`) plus a dump before the first production run.
- **CA13: where the split runs.** Root's page and a job.
- **CA23: the Admin role in a cluster's target.** The confirmation lists the cluster's known
  runners with none pre-ticked; root ticks who gets Admin in the target (and may add others);
  in-app notices, no mail.
- **CA24: types scalawiki doesn't know.** When scalawiki doesn't know a contest's type, its type
  code is the word after "Wiki Loves" in the contest's name, in lower case (`folklore`, `fashion`,
  `science`); no migration (`series.contest_type` is VARCHAR(32)). The proposals then group such
  contests into their own series ("Wiki Loves Folklore in Nepal"). scalawiki may learn these types
  later; their codes would stay the same words. Built in CY-1.

**Decided by the maintainer, 2026-11-18:**

- **CA1: a group entity, the Series.**
  - The maintainer asked: "isn't a group entity a clearer way going forward?"
  - It ties contests by a foreign key. Before, membership came from parsing names and
    countries, which caused most of the hard problems: International, `campaign` precedence,
    unparsed names, retired contests.
- **CA2: the name.** "Series" in English; uk «серія», ru «серия», fr «série».
  - Examples: "Series: Wiki Loves Monuments in Nepal", "Series: Wiki Loves Earth International".
  - "Contest" stays one year's edition, and "campaign" stays reserved (2026-11-05).
- **CA14: Nepal now.**
  - Root creates "Wiki Loves Monuments 2026 in Nepal" now and adds the admin.
  - Rounds 1974 and 1975 stay in contest 466 until the reuse report. 466 stays unassigned (no
    series).
- **CA19: series admins add series admins.**
  - A series admin may add, but not remove, series admins of their own series.
  - Each addition is logged and sends root an in-app notice.
  - Only root removes a series admin.
  - A new grantee gets the same protection (`User.isProtectedTarget`).
  - **Risk:** one compromised series-admin account can add others. Root sees and revokes them on
    `/admin/series` (§5.3; risk in §8).
  - **Limits on the chain** (§3.3):
    - only root may re-grant an account that root revoked;
    - root's revoke offers "also revoke the grants this admin added" (the `granted_by` chain);
    - grantees added by a series admin may add further, at any depth (CA22).
- **CA4: who among contest Admins creates the next year.** Only an Admin of the series' most
  recent contest, when that contest is from this year or last year, the new contest is its
  year + 1 (at most next year), and the inline reuse check finds no sign of reuse.
- **CA6: copying people.** A picker of the previous year's members with nothing ticked, series
  admins shown but disabled, the invitation email box unticked, and in-app notices to those
  added.
- **CA15: one live contest per series and year,** enforced by a database key from the first
  migration.
- **CA16: years a series admin may create:** a free year from last year to next year, and not
  below their own start year (CA20).

- **CA8: a small change log now.**
  - Grants, revokes and start-year changes go into `account_change`, with the new fields.
  - A small `contest_change` table records contest creation, series assignment and identity
    edits.
- **CA3: the role is "Series admin"** (uk «адміністратор серії», ru «администратор серии», fr
  «administrateur de la série»).
- **CA17: reach from the start year on** (re-decided 2026-11-18; not the original
  recommendation).
  - In the series' live contests with year ≥ the grant's start year (CA20), the series admin is a
    full contest Admin: the grant counts for the account rules as if it were a real Admin row,
    including invitations and share links (`mayGrant`, `adminContests`). This holds whether the
    contest was created before or after the grant.
  - Below the start year it gives no account reach (derived Organizer, read-only).
  - The grant date plays no part. Earlier versions (same day): reach only in contests created
    since the grant, later also with year ≥ the start year.
- **CA18: "Take the Admin role here" dropped** (2026-11-18). From the start year on the grant
  already gives everything a real Admin row would, and below it Take was refused, so no purpose
  remains. Earlier version: Take in contests from the start year on that were older than the
  grant, logged, with a notice to root.
- **CA20: one start year per grant** (reworked 2026-11-18; it replaces "year ≥ current − 1 and
  contests created since the grant").
  - `series_admin.admin_from_year`: derived Admin in the series' contests with year ≥ that year,
    derived Organizer (read-only) in older ones.
  - **Default:** the earliest year of the grantee's own real Admin rows in the series' live
    contests (after J-2 joins), capped at last year; with no such row, last year. Computed at grant
    time, shown and editable by the granter.
  - **Bounds:** a series admin who grants (CA19, CA22) may not go below their own start year;
    root may set any year. Only root changes it later, logged in `account_change` with old and
    new values.
  - Real per-contest roles add on top, and the higher role wins. No separate window rule.
  - Account reach follows the same start year (CA17).
- **CA21: "Show them"** (not the recommendation). Account search lists series admins like anyone
  else; `rootShown` stays for root only.
- **CA22: "Yes, any depth"** (not the recommendation).
  - Any series admin may add further series admins.
  - Root's chained revoke along `granted_by` removes a whole branch. It is a required part of
    CY-2.

The first batch (CA1, CA2, CA4, CA6, CA14, CA15, CA16, CA19), CA8 and the second batch (CA3,
CA17, CA18, CA20, CA21, CA22) are decided; CA20 was reworked the same day (one start year per
grant), CA16 reconciled with it, CA17 re-decided (reach from the start year on) and CA18
dropped. The third batch (CA9–CA13, with CA23 added 2026-11-20) was asked one by one after Q0–Q4
and the CY-0 report, and decided on 2026-11-20 as recommended, as was CA24. **No decision remains
open.**

The maintainer asked on 2026-11-18:

> admin was reusing existing contest because we did not support adminship over a
> country/country campaign, so many organisers, instead of asking the root admins to create new
> campaigns each year, found a workaround to reuse existing. Could we plan native support for
> this scenario and migration of rounds that used this workaround?

**The trigger:** the WLM 2026 Nepal report (support diagnostics plan §2).

- **Where the admin worked:** contest 466 on jury.wikilovesearth.org.
  - 466 is an old WLE contest that already held WLE files from Armenia, Sri Lanka, Bhutan and
    other countries. "About 2020 files" in the investigation is a count of files.
  - 466 is Wiki Loves Earth 2020, Nepal (the production copy, read 2026-11-20; its 48 rounds in §4.7).
- **Its earlier rounds:**
  - The investigation named 731, 1597, 1899 and 1900.
  - The access logs of 2026-11-04 to 11-18 show 731, 1597 and 1899.
  - Their years are unknown. 1899 and 1900 may be from 2026, since ids near 1938 are this
    season's.
- **The Nepal rounds:**
  - **1974** was made from all of the contest's files, so jurors saw the old ones.
  - **1975** came out about empty.
- **No WLM 2026 Nepal contest existed,** and only root can create one.

**Builds on, does not reopen:**

- **Terminology** (2026-11-05, the roadmap's "Terminology and the contest list"):
  - **Contest** in the UI;
  - **campaign** only for the Commons campaign config (`contest_jury.campaign`,
    `id.template.*`);
  - **Juror** for the role, and **jury** only for the collective.
- **Roles:**
  - root is the account flag `users.is_root`, never changed by contest-role writes;
  - per-contest roles are in `user_contest` (V61/V62);
  - Admin means one or two setup people; Organizer is read-only plus comments.
- **The multi-role plan:**
  - design S;
  - **O14 / J**: Juror never with Admin, Organizer or root;
  - decision 7: the picker pre-ticks no Organizer or Admin;
  - decisions 5 and 6: union;
  - O1: dump-restore rollback.
- **The Montage comparison** §4.3:
  - an overseer's grant lives in its own table, not in a removable membership row;
  - COI within the grant only.
  - The maintainer's note of 2026-11-10 already anticipated middle admins.
- **Accounts:**
  - the existing-accounts plan (A1–A3) and the account join plan;
  - `UserService.addToContest`;
  - the account change log (V96);
  - no mail without an admin's unticked-by-default choice; in-app notices instead;
  - personal onboarding through share links.
- **Round lineage:**
  - §1.1, the edges;
  - §1.4: `round.created_at` has `ON UPDATE` on every DB (V86), so ids give creation order;
  - §2.6: `round.status` (V105).
- **`round_image`** (V84), and the plan for the top N of another contest's round (2026-10-29).
- **The support diagnostics plan** (#371):
  - H1, H2, H8 and H21 (D-1c);
  - DG10 and DG11, decided;
  - D-1a's `ContestHealth`;
  - D-1b's read-only token, built as V110 (`stack/210-d1b-token-scope`, 2026-11-20; V108 went to
    the quick fixes' NOT NULL `round_user.active`, V109 to CY-1's `Series`);
  - its §8, "Admins can't create contests", is this plan.

## 1. Prior work and terminology

**History:**

- **GitHub issues:**
  - #49 "Support several contests / countries" (closed 2016);
  - #120 "do not import campaign twice" (`f294baef`, 2019);
  - #88 "Campaign grouping/filtering in root admin view" (closed 2020, "can be filtered by year
    and country/type").
- **The comparisons** (untracked, main tree):
  - `docs/comparison/01-wlxjury.md` WJ-F3.1 ("the contest name × year matrix is the only
    equivalent of Montage's series") and WJ-F3.6;
  - `02-montage.md`.
- **The roadmap's terminology and contest-list plan** was never written. §5.3 covers its
  contest-list part.

**Terms:**

- in code: `series`, `series_admin`, `contest_jury.series_id`;
- in the UI: "Series: {name}", "Series admin" (CA3), "previous year's contest".

| Concept | UI | WLX today | Montage | WLM/WLE usage *(general; for the terminology plan to check)* | False friends |
|---|---|---|---|---|---|
| One year's national contest | **Contest** | Contest (`contest_jury`) | Campaign | the national contest of that year | Montage "campaign"; the Commons **upload campaign** (= `contest_jury.campaign`) |
| A contest across years | **Series** (decided) | name × year matrix | Series | "Wiki Loves Monuments in Nepal" | **Montage Series: partial false friend**: global (all of WLM), no permissions; ours is per country or campaign and carries admins. **campaign**: reserved |
| Last year's contest | "previous year's contest" | — | — | the previous edition | the word "edition" isn't used in the UI |
| Per-contest setup person | **Admin** | Admin | Coordinator | national organizer | — |
| Read-only helper | **Organizer** | Organizer | — | "organizer" = the whole team | **Organizer** ≠ Montage Organizer ≠ "national organizer" |
| Admin of a series | **Series admin** (CA3) | — (root only) | Organizer + Coordinator | national or international team | "Organizer", "Coordinator", "Campaign admin" |
| Super-admin | **root** | root | Maintainer | — | — |

## Goals

1. **No reuse.** A team starts each year's contest inside its series, without root and without
   reusing an old contest.
2. **Series admins.**
   - Root grants them per series. Series admins may add more (CA19).
   - Every grant is logged.
   - Series admins never touch root.
   - **Account reach (CA17):**
     - the grant reaches accounts in the series' contests **with year ≥ the grant's start year**
       (CA20), whether created before or after the grant, as a real Admin row would;
     - below the start year, only a real Admin row reaches accounts;
     - protected accounts (root, other series admins) stay out of reach (§3.3).
3. **Last year carried over.** Last year's settings come over, and its people by the admin's
   choice, with no automatic mail.
4. **Existing contests.**
   - Root assigns them to series in bulk from proposals.
   - Reused contests are found and, where it pays, split after a dry run, with a way back.
5. **Grouping.** The contest menu and root's list group by series.

## Non-goals

- Self-service contests.
- Contests outside a series by anyone but root.
- Identity edits by anyone but root.
- Removing files from a contest.
- Round templates.
- Exposing the `rootOnly` `/api` contest endpoints further.
- Per-vhost lists.
- Re-deciding multi-role, the existing-accounts rules or the account change log.

## 2. What exists today

**The contest row** (`contest_jury`):

- `name` (the type as text), `year`, `country` ("International" for international contests);
- `images` and `category_id`;
- `current_round`, `monument_id_template`, `greeting`;
- `campaign` (the scalawiki code, read first by `ContestKind.of`).
- No link between years, no unique identity, no creator.

**Files:**

- **The contest's files** are the `category_members` of its `category_id`. Since the import jobs
  that is the contest's own category `#contest <id>`. Older contests may share a category titled
  by their source.
- **Country categories** are global links, set only for contests of many countries, so a
  national contest's files usually have none.
- **The source:** an import adds files and sets `contest.images`, and admins may change the
  source.
- **That is the workaround:** any old contest can be pointed at a new source.

**Rounds:**

- `round.contest_id` and `number`;
- `previous` and `exclude_rounds` (V95), which resolve within one contest;
- keyed by round id only:
  - `round_user`, `round_image`, `round_image_event`, the counts;
  - `round_criterion`;
  - `vote` (no FK), `vote_aside`, `criteria_rate`;
- `comment` (`contest_id`, `round_id`, `room` = page id; every comment has a round);
- `job.contest_id` and its subject;
- `account_token.contest_id` and `account_change.contest_id`.

**Memberships:**

- `user_contest(user_id, contest_id, role)`, read as `UserContest(userId, contestId, role)`,
  with derived `roles` and `active`.
- **Two callers besides the session use `Secured.inCurrentContest`:**
  - `ApiTokenService` (`:87`);
  - root's `UserController.editableUser` (`:321`).
- **The sessions:** `Secured.sessionUser` (`:583`) and `LoginResults` (`:63`).

**Account rules and protection:**

- **Actor-side rules** read the actor's rows: `canEditAccount` and its copy `mayEditAccountNow`
  (`UserController:1600`), `passwordLinksShown`, `AccountSearch.administered`,
  `ShareLinkService.adminContests`, `AccountTokenService.mayGrant` (raw, at acceptance:
  `:288`, `:375`), `AccountRetirement.contestsOf`, `AccountJoin.scopeOf` / `Side`,
  `AccountJoinController.mayManage`, `JobController` (`:77`, account-kind jobs),
  `OAuthChooserController`, `ApiTokenService`.
- **Target protection is 26 `isRoot` checks on the target account** (at this plan's code; the
  guard spec in §3.3, not this list, is authoritative):
  - `UserService` `:139`, `:171`, `:283`, `:397`, `:869`;
  - `UserController` `invitable` `:977`, `reinvite` `:1404`, role writes `:714`, `:759` and
    `:883`, the users page `:59`, `mayEditAccountNow` `:1600`;
  - `AccountSearch` `:77`, and the SQL behind it, `User.searchAccounts`' `rootShown`
    (`User.scala:737`);
  - `OAuthChooserController` `:50`, `:67`, `:119`;
  - `IdentityLinker` `:87`, `:143`, `:149`;
  - `AccountRetirement` `:116`;
  - `DuplicateAccounts` `:139`;
  - `AccountJoin` `:182`;
  - `ShareLinkService` `:82`, `:86`, `:130` (`TargetRoot`).
- **SQL `is_root` filters on target accounts:**
  - `User.searchAccounts`' `rootShown` (`User.scala:737`), above;
  - the join proposals, `AccountJoinRequestJdbc` `:66` and `:79`.
- **Admin-gated actions that would accept a merged role,** because they check
  `contestPermission(ADMIN_ROLES)` after `Secured.inContest`:
  - `JobController.adminOf` (`:77`) and `AccountJoinController.mayManage` (`:104`);
  - `UserController`'s `editableUser` (`:322`), `changesShown` (`:406`), `searchAccounts`
    (`:83`), and every other `contestPermission(ADMIN_ROLES)` action;
  - `AccountChangeController.changes`;
  - `DuplicateAccountsController.report` (and `:50`).
- **COI:** `CoiMembers.forContest`, in SQL.
- **The menu and hints:** `JurorContests.switcherEntries`, `Secured.deniedRoles` and
  `suggestedAccess`. `UserController.switchContest` (`:1308`) switches contests.

**Who creates contests:** root only (`ContestController`; the `/api` endpoints are `rootOnly`).

**Scale:** contests up to id ~631, ~1975 rounds, ~760k votes.

## 3. Design

### 3.1 The series

**`series`:**

| Column | Meaning |
|---|---|
| `id` | |
| `name` | "Wiki Loves Monuments in Nepal" |
| `contest_type` | a scalawiki `ContestType` code (`wlm`), or for a type scalawiki doesn't know the word after "Wiki Loves" in lower case (`folklore`; CA24), or NULL (any type). VARCHAR(32), not an enum |
| `country` | a country name, "International", or NULL |
| `campaign` | the Commons campaign config code, if any |
| `created_by`, `created_at` | |
| `retired_at`, `retired_by` | |

**Root only:**

- creating, renaming, retiring and un-retiring a series;
- assigning or moving contests.

All of this is logged in `contest_change`.

**Contests in a series:**

- **The link:** `contest_jury.series_id`, a nullable FK with `ON DELETE RESTRICT`.
- **One series per contest.** A legitimate second contest in a year gets its own series: a Q0
  duplicate, or a special contest such as "Ukraine, Animals".
- **One live contest per series and year:**
  - a generated `live_series_id = IF(retired_at IS NULL, series_id, NULL)`;
  - `UNIQUE (live_series_id, year)`;
  - `year` is NOT NULL.
  - Each creation, assignment and move also locks the series row `FOR UPDATE`, so the user gets
    a clear message.
  - **A change from the earlier revision:** outside a series, root's duplicate (name, year,
    country) gets **only a warning**.
- **The previous year's contest is derived, not stored:** the live contest of the same series
  with the next lower year (indexed by the key). So creating a past year, moving, retiring,
  un-retiring and splitting need no recompute.
- **A new contest's identity comes from its series:** name = the type's name, plus the series'
  country and campaign.
- **Root's identity edits in a series:** a change to the type or country of a series contest
  that no longer matches its series is refused. Root moves the contest first.
- **Assigning or moving one contest:** refused only for a positive mismatch, a recognised type or
  country (a known country, or International) that differs from the series'. A contest whose type
  or country the tool doesn't recognise ("USA", "Непал", "Photo contest") may be assigned by root;
  its `contest_change` row says "not recognised; assigned by root".
- **`ContestKind` is unchanged.** Membership never depends on parsing; parsing only feeds the
  proposals (§3.5).

**Retired series:**

- its grants give no access and no protection;
- nothing can be created in it;
- its contests stay live and show under "Other contests";
- it is excluded from `seriesContests` and from COI's series branch (specs);
- un-retiring asks root to re-confirm each grant (the maintainer, 2026-11-24): the grants come back
  "to confirm" (`series_admin.confirm_needed_at`), giving no access and no protection until root
  confirms each; `granted_by` chains are kept; a grant whose granter is still to confirm is refused
  until the granter is confirmed (a revoked granter doesn't block it); an unconfirmed grant can be
  revoked. Built in CY-2.

**Retired contests:**

- their memberships give no access; root may open them read-only (**CY-1 follow-up:** CY-1 hides
  them and takes away members' access, but root keeps write access; a read-only check on root's
  write paths is still to do);
- hidden from menus and lists (root's list has a filter);
- ignored by "previous" and "newest", the key, COI and the H checks;
- still counted by `contestsWorkedIn` and by the account rules on the target's side (a member of
  a retired contest stays out of an admin's reach unless the admin administers it); an actor's
  reach skips them (`UserContest.opens` in `AccountSearch.administered`, `AccountJoin`'s scope),
  and they are no source for "Add people from another contest", root's included;
- retiring is refused while a round is active, paused or distributing;
- un-retiring is refused if a live contest holds the series and year;
- root only, logged.

### 3.2 Creating a contest

| Who | Where | Which years |
|---|---|---|
| Root | anywhere | any |
| A series admin | their series | CA16: a year not taken, from the current year − 1 to the current year + 1, and not below their grant's start year (CA20) |
| An Admin (real row) of the series' **newest live contest** | "Create the next year's contest" | CA4: its year + 1, at most the current year + 1; only if the newest contest's year ≥ the current year − 1 and the inline reuse check passes |
| Anyone else | — | ask root or a series admin |

**CA4 and CA16 with CA20's start year:**

- **CA4 is unaffected.** It is about real Admin rows, and its "year ≥ the current year − 1" is
  its own staleness rule for the newest contest, no longer shared with CA20.
- **CA16 keeps its range** and gains one bound: not below the creator's start year. The creator
  gets a real Admin row (step 4 below), so without the bound a series admin whose start year
  root set to this year could create last year's contest and hold Admin below their start year.
  With the default start year (at most last year) the bound never bites.

**What arrives when:**

- **With CY-3:** root's and the contest Admins' paths, and the people picker.
- **With whichever of CY-2 and CY-3 lands second** (they need both CY-3's form and CY-2's
  `series_admin`, V112 on CY-2's branch):
  - creation by series admins;
  - CA6's disabled grantees in the people picker.

  Until then the picker has no grantees to show.

**The inline reuse check** (CY-1; D-1a's H1 logic; `services.series.ContestReuse`) flags a
contest with:

- an import source since V60, or `contest.images`, of another type, country or year;
- **every round's signals** (the maintainer's refinement of 2026-11-20, R4 in §4.1: one country's
  contest may host rounds of several other countries, its own source still right):
  - a round's category filter naming another type, country or year;
  - of a round drawn from the contest's files (one with no previous round in the contest), its
    files in a category of another identity: another contest's own category (that contest's
    identity) or a country category whose title names one (S2): flagged when such files are both
    at least 5 and at least 2 % of the round's files (`series.reuse.foreignMin`,
    `series.reuse.foreignShare`);
  - a national contest whose rounds name two or more countries by those signals;
- round ids spanning more than the threshold (default 150, `series.reuse.roundIdSpan`) are shown
  beside another sign, **never a flag by themselves**: on the production copy (2026-11-20) 17 of
  239 contests with rounds span more than 150 ids, 13 of them with another sign; the other 4 are
  national contests with a late round.

Its rounds' files are read by keys only (round, contest and `round_image` by primary key,
`category_members` by its page index, in a forced order; `ContestReuseSpec` EXPLAINs it). Measured
on the production copy (V107, 2026-11-20): for every unassigned live contest (912 rounds drawn
from contests' files, 1.35 million files) 4.4 s, so the proposals page runs on the blocking pool
and its confirmation reads only the ticked contests' files; a year's 24 contests (50 such rounds)
142 ms. The contests page's "Reused" badge reads the sources and rounds only (about 2 ms), never
the files; a contest's series page and the proposals read them.

It refuses CA4's path, and it keeps a contest out of the assignment proposals (§3.5).

- **International:** for an International contest, any country of the same type and year counts
  as its own. The same rule applies to S2 and R3 in §4. A spec covers it.

**Where:**

- the series page's "Create the 2027 contest", and the same action on the Rounds page's Pages
  menu;
- the H1 and import-form warnings (DG10) link there only when the source is the series' type and
  country, one year on.

**The form** (`GET/POST /series/:id/contests/new`):

1. **The year.**
2. **The identity,** from the series.
3. **Settings,** copied from the previous year's contest, shown and editable:
   - the greeting;
   - its own monument id template;
   - the source with the year replaced (otherwise "Category:Images from {type} {year} in
     {country}"), checked by DG11.

   Nothing is imported.
4. **People** (CA6):
   - the previous year's active members by role, **nothing ticked**;
   - **holders of a live grant on this series appear disabled** ("has access as series admin");
   - the creator gets a real Admin row;
   - adds go through `UserService.addToContest`;
   - "Send an invitation email" is unticked;
   - each person added gets an in-app notice.
5. **Create.**
   - One transaction:
     - lock the series row;
     - re-check the year;
     - insert the contest, its own category `#contest <id>`, the memberships and a
       `contest_change` row;
     - send root an in-app notice.
   - Then **redirect to `GET /admin/rounds?contestId=<new>`**: the page GET switches the
     contest, the POST doesn't.
   - Root may reassign the new contest's Admins or retire it.

### 3.3 Series admins and authorization

**`series_admin`** (`series_id`, `user_id`, `admin_from_year` (NOT NULL, CA20), `granted_by`,
`granted_at`, `revoked_by`, `revoked_at`, `revoked_by_root`, `comment`):

- **A live grant:**
  - `revoked_at` is NULL;
  - its series is live;
  - its account is not deleted.
- **One live grant per account and series:** a generated `live_grant_series_id =
  IF(revoked_at IS NULL, series_id, NULL)` with `UNIQUE (user_id, live_grant_series_id)`, modelled
  on `live_series_id`.
- **Logging:** each grant and revocation goes to `account_change` and to `security.roles` as
  `series_admin_granted account= series= from_year= by=`. A grant's row records the chosen start
  year and the computed default.
- **At grant time** the page shows:
  - the start year, pre-filled with the default and editable within the bounds below;
  - the grant's **full list of contests and years**, updated as the start year changes: derived
    Admin from the start year on, derived Organizer in older ones, and real Admin rows the
    grantee already holds. A note says the grant reaches the accounts of members of every listed
    contest from the start year on, existing ones included (CA17), except protected accounts and
    members who also belong to a contest the grantee doesn't administer;
  - the contests where the grantee holds a Juror row ("the series won't apply there"; CA7).

**The start year (CA20, reworked 2026-11-18):**

- **The default, computed at grant time** (shown, editable by the granter):
  - the earliest `year` of the series' live contests where the grantee account holds an **active
    real Admin row**, capped at the current year − 1, so a grant never gives less than one to an
    account with no Admin history;
  - with no such row, the current year − 1;
  - **joined accounts count:** J-2 moves a joined account's rows onto the kept account, so past
    years' Admin roles held under separate accounts (by email or wiki account) count once joined.
    Rows of accounts not yet joined don't count: join first, or root lowers the start year after
    the join (below);
  - retired contests, inactive rows and Juror or Organizer rows don't count.
- **Bounds:**
  - **root** may set any year;
  - **a series admin who grants** (CA19, CA22) may set a year from their own start year up to the
    current year + 1. A default below their own start year is raised to it.
- **Later changes: root only.**
  - On `/admin/series`, per grant, with the recomputed default shown (useful after a join).
  - Each change goes to `account_change` (field `series_admin_from_year`, old and new values) and
    to `security.roles` as `series_admin_from_year account= series= old= new= by=`.
  - It takes effect on the next request (derived roles and `seriesAccountContests` are computed
    per request), for account reach as well: lowering it widens reach to older contests' members.
  - It doesn't cascade: the form lists the grants this admin added (`granted_by`) with their
    start years, for root to change each.
- **Joins:** a moved grant keeps its start year. If both accounts hold a live grant on one
  series, the kept account's grant stays and the other is revoked; a join never changes a start
  year by itself.

**Who grants (CA19, decided; limits added here):**

- **Root** grants to any account.
- **A series admin** may add series admins to their own series, at any depth (CA22). Each
  addition sends root an in-app notice.
- **Only root revokes.**
  - A grant revoked by root (`revoked_by_root`) may be re-granted only by root.
  - **The chained revoke (required, CY-2).** Root's revoke offers "also revoke the grants this
    admin added", following `granted_by` to any depth, in one transaction under the series row's
    lock. Specs cover a three-level chain: revoking the top removes all three, and revoking the
    middle keeps the top.
  - **Root's view:** `/admin/series` shows, per series, the grants of the last 24 hours, each
    with who added it.
- **Who may be granted** (checked under the account's row lock):
  - an **active real member** of one of the series' live contests;
  - **refused:** deleted accounts, accounts being retired by a join
    (`AccountJoinJdbc.retiring`), root accounts, the adder themself, and existing grantees.
- **How the adder picks the account:** from the members of the series' contests, listed on the
  series page. Account search also shows series admins (CA21), but the grantee rules above still
  apply.
- **Joins and retirement:**
  - the account join (J-2) moves the joined account's grants to the kept account, or revokes
    them, in its own transaction;
  - retiring an account (A-4) revokes its grants.

**Session reach, kept apart from every other path:**

- **`Secured.inCurrentContest` stays raw.** `ApiTokenService` and `editableUser` still call it.
- **A new `Secured.withSeries(user, currentContestId)`:**
  - it adds `User.seriesContests` and `User.derivedRoles` (per contest, separate from the real
    `roles`);
  - it resolves the current contest over the real contests and the series' contests, real
    ones first, so a contest switched to through `maySwitchTo` sticks;
  - it is called only from `Secured.sessionUser` and `LoginResults`. API tokens and root's edit
    form never get it.
- **`User.userContests` and `User.roles` stay real everywhere.**
- **Only these read `seriesContests` and `derivedRoles`:**
  - `Secured`: `inContest`, `permitted`, the permission checks below, and a new
    `Secured.maySwitchTo(user, contestId)`, which `UserController.switchContest` calls;
  - `JurorContests`;
  - the series controller.
- **The guard spec** scans all of `app/` against that allowlist. It also fails on a call of
  `withSeries` from `ApiTokenService` or `UserController`.
- **Signing in:** `LoginResults` calls `permitted`. A user with only series reach has no
  `contestId` and lands on their series page.

**Permissions: real rows by default, derived only by opt-in:**

- **`contestPermission` accepts real rows only.**
- An action where the merged role is enough opts in explicitly:
  `ContestPermission(roles, contestId, derived = true)`.
- **A raw `Secured.inContestReal`** (real rows only) is used by `JobController.adminOf` and
  `AccountJoinController.mayManage`.
- **A guard spec lists every `derived = true` action.** A new opt-in needs the list and its
  review updated.

**What a series gives in a contest (CA20):**

- **Admin, derived,** in the series' live contests with year ≥ the grant's `admin_from_year`
  (the start year, above), with account reach there too (CA17), whether the contest was created
  before or after the grant.
- **Organizer, derived** (read-only), in older ones.
- **Real roles add on top, and the higher role wins:** a real Admin row in a contest below the
  start year gives Admin there; a real Organizer row from the start year on gives derived Admin.
  There is no separate window rule: the contest's creation date and the current year play no
  part in the derived role.
- **Exceptions:**
  - a real Juror row wins, and the series doesn't apply there (CA7, O14);
  - a real inactive row doesn't block the derived role.
- **Earlier version** (decided 2026-11-18, replaced the same day): derived Admin for year ≥ the
  current year − 1 and for contests created since the grant. It was dropped because the window
  and the grant date were two overlapping concepts, and because accounts once separate per year
  and later joined bring real past Admin roles that should count. With CA17 re-decided the same
  day, the grant date plays no part in account reach either.

**Every action, classified:**

| Action | Derived role enough (`derived = true`) | Real Admin row needed, or a series contest with year ≥ the grant's start year (CA17) |
|---|---|---|
| Rounds (create, edit, start, stop, distribute, jurors), contest settings, imports, the image cache, criteria, galleries, statistics, conflicts, round review | yes | — |
| Role edits of existing members **between non-Admin roles** (Juror, Organizer, COI groups), with the target check below | yes | — |
| **Any change to or from Admin** (promote, demote, remove an Admin) | — | yes: it creates or removes a real Admin row with account reach, so it goes through the account rules |
| Adding people: the add row, import users, add-existing, add-wiki-confirmed | — | yes (they may issue invitations) |
| Invitations: issue, `reinvite`, cancel | — | yes. Today the check is raw only at acceptance (`AccountTokenService:288`/`:375`), so an invitation from a derived Admin would be mailed and then grant nothing |
| Share links, password links | — | yes |
| `editableUser`'s Admin branch (`:322`), `changesShown` (`:406`), `AccountChangeController.changes` | — | yes |
| `UserController.searchAccounts` (`:83`), `DuplicateAccountsController.report` and `:50` | — | yes |
| Account-kind jobs (`JobController.adminOf:77`), account joins (`AccountJoinController.mayManage:104`) | — | yes (`inContestReal`) |
| Every other `contestPermission(ADMIN_ROLES)` action in `UserController` | — | yes, unless listed above as derived |

In a series contest with year ≥ the start year, created before or after the grant, the
right-hand column is satisfied by the grant itself (CA17). Contests below the start year are
read-only unless a real Admin row is held there.

So for a series admin both columns now hold in the same contests. The split stays because the
mechanisms differ: the left-hand column reads the session's `derivedRoles` (`derived = true`);
the right-hand one goes through the account rules, whose only derived input is
`seriesAccountContests`, read from the database so that `mayGrant` gives the same answer at
acceptance time.

**Account reach (CA17, re-decided 2026-11-18: reach from the start year on):**

- **One named function:** `SeriesAdminJdbc.seriesAccountContests(user)`.
  - For each live grant of the user, it returns the series' live contests with year ≥ that
    grant's `admin_from_year`: the same contests where the grant gives derived Admin (CA20).
  - **The start year is the only rule.** A contest counts whether it was created before or after
    the grant; `contest_jury.created_at` (kept for root's `/contests` list) and `granted_at` are
    not read. A contest root back-fills below the start year gives derived Organizer and no
    reach.
  - Excluded: retired contests, a retired series, revoked grants.
  - It reads the database, not the session, so it gives the same answer at acceptance time
    (`mayGrant`) as when an invitation is issued.
  - **Earlier versions** (2026-11-18, replaced the same day): only contests created since the
    grant; then contests created since the grant **and** with year ≥ the start year.
- **The account rules count those contests as if they were real Admin rows,** next to the
  actor's real Admin rows:
  - `canEditAccount` and `mayEditAccountNow`, `passwordLinksShown`;
  - `AccountSearch.administered` (with `describe`, `WikiTakenPanel` and `DuplicateAccounts`);
  - `ShareLinkService.adminContests`, `AccountTokenService.mayGrant`;
  - `AccountRetirement.contestsOf`, `AccountJoin.scopeOf` / `Side`, `AccountJoinController`;
  - `JobController`'s account-kind jobs, `OAuthChooserController`.

  It is their only derived input. The guard spec allows `seriesAccountContests` in these rules
  and nothing else from the series: no `seriesContests`, no `derivedRoles`.
- **Below the start year the grant gives no account reach.** Only a real Admin row the series
  admin already holds there does (root adds one, or lowers the start year).
- **The protections stay:**
  - root accounts and other series admins are protected targets (`isProtectedTarget`), out of
    reach of every series admin;
  - a target's contest set still includes all its memberships and `contestsWorkedIn`. A juror who
    also worked in an older or unrelated contest stays out of reach unless the series admin
    administers that contest too (the `forall` rule).
- **API tokens** still get no series reach. The `/api` has no account endpoints, and
  `withSeries` isn't applied to tokens.

**Protected targets: one predicate:**

- **`User.isProtectedTarget(account)`:** root, or a live grant on a live series.
- **It replaces the target `isRoot` checks** listed in §2 (26 today), except the role writes,
  which get a series-scoped rule (below).
- **On write paths** it is read under the account's row lock (`User.lockRow`).
- **What it covers:**
  - only root may edit a protected account's details, link it, invite it, retire it or join it;
  - in the series' contests, no one but root may **add it as a Juror, or remove or downgrade its
    Admin rows**.
  - Admins of unrelated contests may still remove its Juror or Organizer rows there, as for any
    member.
- **The role writes** (`UserController` `:714`, `:759`, `:883`) are not a straight swap of
  `isRoot` for `isProtectedTarget`. They keep `isRoot` (root accounts: root only) and add
  `User.seriesProtected(account, contestId)`: true when the account has a live grant on the
  contest's live series. When it is true, a non-root actor may not:
  - add the account as a Juror;
  - remove or downgrade its Admin row.

  In any other contest, an Admin may change or remove the account's Juror or Organizer rows as
  for any member. It is read under the account's row lock.
- **The guard spec** scans all of `app/` against an allowlist, for both:
  - a bare `isRoot` on a target account (actor checks only);
  - SQL filtering target accounts on `is_root`.

  The allowlist names the two SQL sites in §2:
  - `User.searchAccounts`' `rootShown`, which stays for root only: series admins are shown
    (CA21);
  - `AccountJoinRequestJdbc` `:66` and `:79`, which also filter live grants: join proposals
    involving a series admin are root's, like root accounts.
- **AccountSearch** lists series admins like anyone else (CA21). The protection still blocks
  role and account changes by non-root actors. The residual risk is visibility: others can see
  who the series admins are.

**"Take the Admin role here" (CA18): dropped 2026-11-18.** From the start year on the grant
already gives everything a real Admin row would (CA17), and below it Take was refused, so it has
no purpose left. There is no Take action, notice or spec. Earlier version: offered in contests
from the start year on that were older than the grant, logged, with a notice to root.

**COI:** `CoiMembers.forContest` gains a branch for the live series admins of the contest's live
series. Specs cover a series admin with no real row, and a retired series.

**Design note (the security review of #140, CY-2):**

- **CA7 is per account.** A series admin who holds a Juror row in contest C has no derived role or
  account reach in C, but may still add or invite another account in their reach and grant it
  "Series admin": that account then has derived Admin in C. A determined series admin can so reach
  a contest where they judge. Accepted (series admins are trusted per series, CA19); the
  mitigation is root's 24-hour view per series and the 30-day notices of grants series admins
  added on `/admin/series`, and root's chained revoke.
- **Protection outlasts access.** An unrevoked grant protects its holder whether it is live,
  waiting for root's confirmation or of a retired series (protection only restricts others); only
  a live grant gives access. The role writes stay as above (the maintainer, 2026-11-24, rather
  than the review's "only root changes a protected account's roles"): in the series' contests no
  one but root makes it a Juror or removes or lowers its Admin row; elsewhere its Juror and
  Organizer rows change as any member's.
- **A juror of an open round sees only their own votes in it** (the maintainer's conflict-of-interest
  rule, 2026-11-24, limited the same day to open rounds), whatever their contest role: a contest
  Admin or Organizer, a series admin by a derived or real role, root. Open: `Round.openCond`
  (active, being created or reviewed, or paused by a freeze while open); once the round is closed
  (stopped, finalized, cancelled) its results are visible as to any admin, so the round form and the
  round review page, which read a closed previous round, need no rule (the form shows no count of
  the previous round's results). A juror of the round: a `round_user` row, active or stopped, or
  votes of their own in it; the round menu opens such a round on the viewer's own votes. One check, `GalleryService.othersVotesVisible`, guards every place that shows
  other jurors' votes (the galleries as another juror or all, by rate, file list, CSV, comments
  report, the large view, a monument's files, the round's authors with votes, the statistics'
  selections and totals, the organisers' bar); each juror's progress counts stay. With CA7 this
  closes most of a juror-admin's view of their own round; the sock route above stays as accepted.

**Visible to the contest:**

- **The users page** shows "Series admins: …": the names, with no edit control for anyone but
  root.
- **The contest's change log view** includes series grants and revocations.

**Menu and hints:**

- **Real rows** are grouped by series.
- **One entry per granted series,** "Series: Wiki Loves Monuments in Nepal (Series admin)",
  linking to the series page. Its contests are never listed flat.
- **`deniedRoles`** names the series, and **`suggestedAccess`** offers the series page.

**A series admin may:**

- act as derived Admin (from the start year on) or Organizer (below it) (CA20) in the series'
  live contests, except where they hold a Juror row;
- create contests (§3.2, once both CY-2 and CY-3 have landed), not below their start year;
- act on accounts in the series' contests with year ≥ the start year, whether created before
  or after the grant (CA17), except protected ones;
- set a grantee's start year at grant time, not below their own;
- add series admins at any depth (CA19, CA22).

**A series admin may not:**

- remove series admins;
- create, rename, retire or reassign series;
- assign contests;
- edit identities;
- touch root;
- change a start year after the grant (their own or anyone's);
- reach accounts beyond their real rows and `seriesAccountContests` (CA17), so not below the
  start year;
- see global jobs or other contests' denials.

### 3.4 Migrations

**Numbers:**

- **V108 is taken** by the quick fixes' NOT NULL `round_user.active` (`stack/208-quick-fixes`);
  **V109 by CY-1's `Series`** (`stack/209-series-cy1`, built 2026-11-20). D-1b, which reserved
  V108, took V110 (`stack/210-d1b-token-scope`, built 2026-11-20).
- **Flyway refuses an older version after a newer one** (`outOfOrder = false`,
  `db/Migrations.scala:41-42`). So each PR **takes the next free number when it merges**: if
  D-1b's migration hasn't merged before CY-1, CY-1 either waits for it (it depends on D-1a anyway)
  or takes its number and D-1b renumbers.
- **The numbers below:** CY-1's V109 is fixed (no other branch had it, 2026-11-20). CY-2 and
  CY-4 take the next free number at their merge. On 2026-11-24 CY-4 (#124, draft) holds V111 on its
  branch and CY-2 V112; whichever merges second renumbers to above master's top first. Earlier: V111
  and V112 once D-1b had V110, one lower
  each if CY-2 or CY-4 merges before D-1b and D-1b renumbers instead.

| | PR | Content |
|---|---|---|
| V109 `Series` | CY-1 | Table `series`. On `contest_jury`: `series_id` (FK RESTRICT), `created_at TIMESTAMP(3) NULL` (no `ON UPDATE`), `created_by`, `retired_at`, `retired_by`, the generated `live_series_id` and `UNIQUE (live_series_id, year)`. Table `contest_change(id, contest_id, series_id, actor_id, kind, batch_id, detail JSON, comment, created_at)`, kinds `created`, `identity`, `series_assigned`, `series_unassigned`, `series_changed`, `split_out`, `split_in`, `split_undone`, `retired`, `unretired` (CA8, decided) |
| V112 `Series_admin` (next free at merge; V112 on its branch) | CY-2 | Table `series_admin` with `admin_from_year` (NOT NULL, CA20), `live_grant_series_id` and its unique key. `account_change.field` adds `series_admin_granted`, `series_admin_revoked` and `series_admin_from_year` (keeping V106's and V107's) |
| V111 `Contest_split` (next free at merge; V111 on #124's branch) | CY-4 | `contest_split` and `contest_split_item` |

Each migration is rerunnable and has a FlywayUpgradeSpec case.

### 3.5 Assigning existing contests (CY-1, run on production right after)

**Proposals** (`/admin/series/assign`):

- contests are grouped across years by type (`ContestKind`) and country, with "International"
  taken literally (Q5);
- each proposal shows a suggested name and its contests by year;
- if a matching series already exists, it is offered first.

**Root confirms in bulk**, with one checkbox per proposal; root may rename a proposal or drop
contests from it. **All or nothing against what root saw:** the proposals are computed again; a
proposal gone, another series offered than the page showed, or a ticked contest no longer in its
proposal (assigned, retired, flagged or grouped otherwise meanwhile) refuses the whole
confirmation, naming the contest, and nothing is written.

- **The confirmation lists the grants affected:** who gains reach to which contests, as derived
  Admin or Organizer by each grant's start year (CA20). Derived Admin includes account reach over
  the contest's members (CA17), so assigning an old contest at or after a grant's start year
  widens that grant's account reach.
- **One confirmation is one batch** (`batch_id`). **"Undo batch"** writes `series_unassigned` for
  each contest of it (those changed since are left and listed). A series the batch created stays,
  empty: root retires it if it isn't wanted.

**Never proposed** (root handles them one by one):

- unparsed names;
- two live contests of one type, country and year;
- unusual country values;
- contests flagged by the inline reuse check (its round signals included, so an R4 host is
  never in a plain series assignment) or, later, by the CY-0 report. **466 stays unassigned** and
  goes through §4.

**Moving a contest between series** (root):

- the confirmation lists the series admins who lose or gain access (account reach included, by
  each grant's start year), and any real Admin rows they hold there, for root to keep or remove;
- the key is re-checked;
- "previous" is derived, so nothing else changes.

Unassigned contests work as today. Only root creates their next year.

### 3.6 Vhosts

There are no per-host lists. The create form pre-selects the type by host when there is a
choice:

- jury.wikilovesearth.* and jury.wle.org.ua → WLE;
- jury.wlm.org.ua → WLM.

## 4. Migrating reused contests

### 4.1 Files: three buckets

Each file is classed by **S2**: a link to a country category whose title names a type, year and
country. For an International target, any country of its type and year matches.

| Bucket | Rule | On a move |
|---|---|---|
| **matching** | the target's type, year and country (International: any country) | linked into the target's own category |
| **foreign** | another identity | never linked |
| **unclassified** | no such link (usual for national contests) | never linked; not foreign |

**Kinds of reuse:**

- **R1:** the same country, other years.
- **R2:** other countries or types (466).
- **R3:** foreign files above a small share (default 2 %) of the S2-classed files. R3 is never
  moved and stays as history (round 1974).
- **R4: a national contest hosting other countries' rounds** (found by the maintainer,
  2026-11-20). One country's contest (for example Nepal's) holds whole round clusters of several
  other countries, each cluster of one other identity. Not R3: within each cluster the files are
  mostly one identity, only not the host's. Handled per cluster:
  - **the target identity comes from the cluster's own signals,** never from the host contest's
    identity: the cluster's dominant S2 identity, its S5 import sources, and its S6 juror set
    (jurors of one country's earlier rounds); the report shows the signals that agree and those
    that don't, and root confirms the identity;
  - **the buckets are computed against the cluster's own target,** not the host's: a Bhutan
    cluster's matching files are Bhutan's, and the host's own country is foreign to it;
  - **memberships follow the cluster:** a move gives the cluster's jurors (its `round_user`
    accounts) the Juror role in the target contest, by `UserService.addToContest`, with in-app
    notices and no mail; the people who ran those rounds (the rounds' `status_changed_by`, the
    jobs' `created_by`) are listed for root to give the Admin role there (**CA23, decided
    2026-11-20:** none pre-ticked, as multi-role decision 7 and CA6; root ticks who gets Admin and
    may add others; in-app notices, no mail). Their memberships in
    the host contest stay as they are (no automatic removal) and are listed for the host's Admin
    to keep or remove;
  - **COI and regions follow the target:** the moved rounds' conflicts of interest are computed
    against the target contest's members (`CoiMembers.forContest`) and their region menus from
    the target's identity (`RegionScheme`), not the host's;
  - the host keeps its own clusters, and once only those remain it can be assigned to its own
    series (§4.3).

**Filling the target:** re-importing its own Commons source fills its file set reliably. The
report offers this.

**What still reaches the target:** foreign and unclassified files stay in the moved rounds'
`round_image` rows and, through `previous`, reach later rounds. They are listed so that admins
can disqualify them.

### 4.2 Detection

**A cluster** is a connected component under `previous`.

- `exclude_rounds` edges don't join clusters.
- An `exclude_rounds` edge from a newer identity to an older one is cut at the move, and the cut
  is recorded.

| Signal | Source | Use |
|---|---|---|
| S1 file era | the median page id of the cluster's largest round against each year's centre (Q1 tuned, below; the nearest) | the likely year; never links |
| S2 country categories | the files' category titles | the buckets |
| S3 round id era | round ids | report |
| S4 `vote.voted_at` | `vote` | since V97 only |
| S5 import sources | `job.params.source`, `contest.images` | since V60 only |
| **S5r round source** | `round.category` of the cluster's first round (its import source: "Category:Images from Wiki Loves Earth 2024 in Nepal") | **the strongest per-cluster signal for old rounds**, which have no V60 job params (466's every year, §4.7); CY-1's inline check reads it too |
| S6 juror sets | `round_user` | supporting |

`round.created_at` is not used: it is unreliable (466's 1601 was created 2026-04-08 but chains
from 2025's 1597; 1381 was created in 2025 from 2024's rounds).

**Q1 tuned (CY-0, the production copy, 2026-11-20):** per-year minimum and maximum page ids are
spoiled by a few files of other years and by test ids up to 9.2·10¹⁸ (2016's maximum), while each
contest's median page id falls in a narrow band per year (2020 about 92 M, 2021 107 M, 2022 121 M,
2023 134 M, 2024 150 M, 2025 171 M, 2026 195 M). So a year's centre is the median of its contests'
medians (contests whose round ids span more than 150 left out, as Q1 says), and a value's year is
the nearest centre, none when it lies farther than the widest gap between two years (a test id).
S3 does the same with round ids.

**How the report names a cluster's identity** (`ReuseReport`): the first round's category filter
(S5r), then the identity most of its files' categories name (S2: of the "Wiki Loves <type> <year> in
<country>" categories, the type and year whose categories hold the most files, each file counted
once over them all, if more than half of the cluster's files, and its country if that country's
categories hold more than half of them, again each file once, else of many countries); missing
parts are the host's type and country and S1's year. **Root's choice** when S5r and S2 name a part differently, when another round's filter does,
when S1 places the files in another year than the one named, or when S1 alone places a cluster no
signal names in another year than the host's (S1 never links). In a contest not of one known
country (international, or a region such as "Africa"), another year's or type's cluster whose
signals name one country is root's choice too, proposed as of many countries (its national
contest's, or an international contest's country round?): never sent to a national series
automatically. A country written as the host's own ("Wales" in a Wales contest, which scalawiki
doesn't resolve) is the host's; a cluster naming no country there keeps the host's place, its own
series' next year. S5 (import sources naming it), S4 and S6 are shown, never
decisive.

**Defaults the report applies per cluster** (from 466, §4.7):

- **types beyond WLE and WLM** (Wiki Loves Folklore, Wiki Loves Fashion, …): a cluster's
  identity is the "Wiki Loves <type> <year> in <country>" its signals name, whatever the type
  (`ContestIdentity` already parses any type word), and its target series is of that type;
- **an empty cluster** (its rounds have no files, as 466's WLE 2023 Philippines rounds 1250–1253
  and 1258): **leave** by default, listed, never moved;
- **signals that disagree** (466's 1255: its category names "2023 in Philippines", but its 1808
  files are the count of Nepal's 1213 and 1254, so likely Nepal's): **flagged for root's manual
  choice**, with each signal shown, never moved automatically.

### 4.3 The dry-run report (CY-0)

**Where:**

- root's `/admin/contests/reuse`, with one page per contest;
- JSON through D-1b's token (`wlxjury-diag reuse`): a root-only `GET /api/diagnostics/reuse`
  beside D-1b's `recent` (`api.Api`, its JSON in `api.DiagnosticsJson`), and a `reuse` entry in
  the CLI's `COMMANDS` (`scripts/diagnostics/wlxjury-diag.py`), with its tests;
- a cached `contest_reuse_scan` job, one contest per step. It reuses D-1a's H1/H2 and is H21's
  full form.

**Built (CY-0, `stack/212-series-cy0-report`, 2026-11-20):** `services.series.ReuseReport` (one
contest's clusters, signals, identity, buckets, proposal and people), `ReuseScanHandler` (the job:
every contest's era sample, then every contest's report, its summaries kept in the job's state;
no contest, root's; a step only reads and writes nothing but the job's own row; the pages, the API
and the series proposals read only the latest finished scan's results, a running scan showing its
progress; the pages and the API read in `DB.readOnly` sessions), `ReuseReportController` (the two pages and
"Scan every contest", root only), `GET /api/diagnostics/reuse` and `GET /api/diagnostics/reuse/{id}`
(both open to D-1b's read-only token, root's only), `wlxjury-diag reuse [id] [--verbose]`. The
inline check is reused for the contest's signs (`ContestReuse.check`), and the series proposals now
also leave out the contests the latest scan reports (§3.5). No migration.

**What it lists for the third batch's decisions** (asked after these numbers and decided on
2026-11-20, never by it): per
cluster the rows a move would change (rounds, comments, jobs; votes untouched: CA12), its jurors
with their host role, deleted or protected accounts and a conflicting role in an existing target
(CA11), who ran its rounds (status changes, jobs; CA23), and per contest the files only moved
rounds use (CA10); the totals give the scale for CA9 and CA13.

**Per cluster it shows:**

- rounds, jurors, votes, comments;
- the buckets;
- S1–S6;
- the verdict;
- the proposed **series and year**, and the action. For an R4 host, each cluster's proposed
  identity is its own (§4.1), never the host's, with the signals behind it.

**Actions:**

- **move** to a new contest of the right series and year (the default; root creates a missing
  series on confirmation);
- **leave** (R3 always);
- **start clean** (for Nepal, decided: 1974 and 1975 stay in 466).

Once only its own clusters remain, the source contest can be assigned to its own series.

### 4.4 Apply (CY-4, conditional on the report)

Root confirms one contest's plan, and a `contest_split` job runs it.

**The target:**

- **by default, a new contest in the series and year** (the series row locked, the key
  holding);
- an existing contest only with root's explicit confirmation, which sends an in-app notice to
  its Admins.

**Preconditions,** checked before step 1 and again under lock in step 4:

- the plan hash matches;
- no cluster round is distributing or frozen, or has an active job;
- only `exclude_rounds` edges are cut;
- the target has no round referring to the source.

**Steps:**

1. **Pause and create** (one transaction):
   - **pause** the cluster's active rounds (V105 `paused`) and record their prior status;
   - send the source's Admins an in-app notice;
   - create the target, or lock the existing one, always with **its own category**;
   - write `contest_change`.
2. **Files:** `INSERT IGNORE` only the **matching** files into the target's own category, in
   chunks with a cursor. The source keeps its links (CA10).
3. **Jurors and admins** (one transaction):
   - give each `round_user` of the cluster the Juror role in the target, and the Admin role to
     those of the cluster's admins root ticked (R4, §4.1; CA23); host memberships stay and are
     listed, never removed;
   - skip deleted accounts, O14 conflicts and protected targets;
   - list everyone else for root;
   - in-app notices, no mail;
   - `split_item` rows in the same transaction.
4. **Move** (one transaction):
   - lock with `FOR UPDATE` on the cluster's rounds and the target's row, plus the dedupe-key
     check (`RoundConflicts.gate`'s pattern), then re-check the preconditions;
   - update `round.contest_id`, `number` (after the target's last), `created_at = created_at`
     and `exclude_rounds` (without the cut edges);
   - set `comment.contest_id` by `round_id`, and `job.contest_id` by round subject;
   - clear the source's `current_round` if it is in the cluster;
   - **resume** the paused rounds;
   - write all old values to `split_item`.
5. **Afterwards:**
   - refresh the caches;
   - write a `round.admin` INFO line;
   - notify the target's Admins.

**Side effects, listed:**

- per-file comment threads split;
- `contestsWorkedIn` moves;
- share links and invitations are rechecked at use;
- COI people listed;
- "Distribute new files" reads the target's category.

### 4.5 Safety and Undo

**Before applying:** a plan hash, and a dump before each batch (its operation id is kept), the first
before the first production run (CA12, decided 2026-11-20: Undo plus a dump).

**Undo (CA12) reverses `split_item`:**

- the rounds, with their number, status, contest and `exclude_rounds` (pause and resume);
- the **comments and jobs, by `round_id` and subject**;
- `current_round`;
- the memberships it created, if unused, otherwise listed;
- **the file links are kept**;
- a created target is **retired**.

Undo is refused once the target has rounds referring to moved ones.

**An active round in a cluster to move** (466's 1900, WLE 2026 Bhutan, active with 965 files on
the copy): step 1 pauses a cluster's active rounds for the move and resumes them after (§4.4),
which jurors notice. So the confirmation names each active round, and the apply is **refused
unless root ticks "pause it for the move"** for it, or stops it first; the plan hash covers the
choice.

**Why not O1's dump restore only:** the split runs online, and a restore would lose everyone's
votes since.

**Scale:** a handful of rows per move, and **no vote row touched**.

### 4.6 Links after a move

- **Round-id URLs keep working.**
- **Contest-qualified URLs** naming the source and a moved round redirect, through
  `split_item`:
  - `/contest/:id/files?round=R`;
  - `/admin/rounds/edit?id=R&contestId=`;
  - `/contest/:id/round/:rid/images/cache/*`.
- **The source's `/admin/rounds`** shows a note.
- **Sessions:** a moved round's first page GET switches to the target.

### 4.7 How many are affected

- **Apache logs, 2026-11-04 to 11-18:** 13 contests were active.
  - Only **466** shows old and new rounds together.
  - 619–631 only show this season's rounds.
- **One confirmed case, and a second kind** (R4, the maintainer, 2026-11-20): a national contest
  hosting other countries' rounds, each such host holding several clusters to move.
- **The worked example: contest 466** (Wiki Loves Earth 2020, Nepal; the production copy at V107,
  read 2026-11-20). 48 rounds from 2020 to 2026, chained by `previous`; the first round of each
  year has `round.category` = "Category:Images from Wiki Loves Earth <year> in Nepal" (S5r):
  - **WLE Nepal, its own type and country, other years (R1):** 2020 (rounds 731–765), 2021
    (962–996, and 997 "Intl Round", created in 2023 from 972), 2023 (1213–1217, 1254), 2024
    (1366–1381), 2025 (1592–1607), 2026 (1882–1899). Proposed: 2020 stays (466's own year); each
    other year moves to the WLE Nepal series' contest of that year.
  - **Other types (R2):** Wiki Loves Fashion 2022 (1172, 1173, "…in the Phil…") and Wiki Loves
    Folklore 2026 Nepal (1817, 1818, 1831): each to a series of its own type.
  - **Other countries (R4):** WLE 2023 Philippines (1250–1253 with no files, and 1258 with
    "Category:WLE PH 2023 Quality Photos" and no files: empty, **leave**) and WLE 2026 Bhutan
    (1900, **active** on the copy, 965 files: moves only with root's "pause it for the move", or
    once stopped, §4.5).
  - **Disagreeing signals:** 1255 "WLE NP Test" (category "…2023 in Philippines", 1808 files, the
    count of Nepal's 1213/1254): flagged, root chooses.
  - So one contest needs about a dozen moves into six or more series, which "start clean" can't
    replace.
  - Q0–Q4 on the production copy give the count (Q5 serves the assignment proposals).
  - **CY-4 will be built** (CA9, decided 2026-11-20 on the CY-0 report's numbers below): R4 hosts
    can't be fixed by "start clean", since their clusters are other countries' whole contests.
- **The dry run on the production copy** (CY-0, the copy at V107, 2026-11-20, SELECTs only in a
  session READ ONLY on the server; the report's code through a harness, `ReuseCopyHarness`, since
  the copy predates V109; rerun after the review's fixes: distinct S2 counts, international hosts,
  "the Philippines" read as national):
  - **286 contests** (239 with rounds): **246 with nothing to report**, 18 with the inline check's
    signs only (mostly international contests' country rounds and old sources), **22 reported**:
    14 for root's choice, 7 with clusters to move, 1 to leave.
  - **Their 275 clusters:** 65 their own, 59 with no signal (stay), 71 R1, 73 R2, 7 R4; proposed
    67 move, 31 root's choice, 53 leave (99 empty, 17 with mixed files, R3), 124 stay.
  - **46 moves** (one per target series and year) from **15 contests**; **2 R4 hosts** (466 and
    489, WLE 2021 Moldova); **7 active rounds** in clusters to move (907, 1046, 1235, 1358, 1654,
    1900, 1967), so a move there needs stopping first (§4.5). No international contest holds
    another year's or type's country rounds (the rule that makes them root's choice found none).
  - **What a move touches:** 62,930 files to link into targets, 172 jurors to add (89 of them judge
    only moved rounds in their host), 52,567 files the sources would keep for moved rounds only
    (CA10), 1,052 rows (rounds, comments, jobs; no vote). Only 1 of the 67 clusters to move has a
    known runner (CA23). 466 alone: 13,431 files, 10 jurors, 67
    rows.
  - **466 as predicted:** 22 clusters (rounds without `previous` are clusters of their own, so a
    year can be two: 2025 is 1592, 1593 and 1594); 2020's two stay; 8 moves (WLE Nepal 2021, 2023,
    2024, 2025, 2026; WL Fashion 2022 Philippines; WL Folklore 2026 Nepal; WLE 2026 Bhutan, active
    1900); 1250–1253 and 1592 empty, left; 1255 root's choice (S5r Philippines, S2 Nepal); 1258
    stays (its filter "WLE PH 2023 Quality Photos" names no year, so no identity: shown, empty).
  - **Against Q3 and Q4:** of Q3's 17 contests whose round ids span over 150, 12 are reported
    (the other 5 are clean: national contests with a late round or old rounds of their own); Q4
    (4.7 s) finds 22 contests with over 20 file links to another year's categories, 13 of them
    reported, the others contests whose files carry old categories but whose rounds don't (a Ghana
    contest's category is an old shared one).
  - **Cost:** the eras pass 4–6 s over all contests, the reports 50–64 s (median 76–99 ms a
    contest, the largest about 2 s, contests 603 with 118 k files and 116; runs on a laptop vary), each one job step; the file reads use keys
    only on the copy (`round_image` PRIMARY, `category_members` by page).
  - **A year can be split across actions:** 466's 2025 is three clusters (rounds without `previous`
    are clusters of their own), 1592 empty and left, 1593 and 1594 moved; CY-4's confirmation shows
    them side by side under the one target, and root may move an empty one with the others.
  - So the numbers support **CY-4** (46 moves in 15 contests, 2 R4 hosts), and CA9 decided it
    (2026-11-20).

## 5. UI

### 5.1 The contest menu

- **Real memberships,** under "Series: …" headings:
  - newest year first;
  - years before the last two under "Earlier years";
  - unassigned contests and those of retired series under "Other contests".
- **A series admin** gets one entry per series.
- **Unchanged:** with a single entry there is no menu. Root uses `/contests` and `/admin/series`.

### 5.2 The series page (`GET /series/:id`)

**Who sees the whole page:**

- root;
- the series' admins;
- the Admins (real rows) of its current and last year's live contests.

Everyone else with a contest in the series sees only their own contests' rows.

**Content:**

- one row per live year: the contest, rounds, files, the active round, the Admins;
- the series admins, with who added each and when, and each one's start year (CA20).

**Actions:**

- "Create the 2027 contest";
- for series admins, "Create a contest" and "Add a series admin" (CA19), the latter with the
  start year pre-filled (§3.3).

### 5.3 Root's pages

- **`/admin/series`:**
  - the series list, with create, rename, retire and un-retire;
  - grants, with revoke and the chained revoke, including those added by series admins, flagged
    with who added them (the `granted_by` chain shown as a tree);
  - each grant's start year, with root's edit and the recomputed default (CA20);
  - per series, the grants of the last 24 hours (CA22);
  - assignment, with batch undo.
- **`/contests`:**
  - a series filter and column;
  - beside the Series menu, root's **Manage series** link to `/admin/series` (the maintainer's
    decision of 2026-11-20: series are not in root's top bar or Pages menu); each contest's series
    page from its row's series column;
  - created by and at;
  - the previous year's contest;
  - a "reused" badge;
  - the creation notices;
  - retired contests behind a filter.

### 5.4 Messages and docs

- **Messages:** keys in en, uk, ru and fr ("Series" / «серія» / «серия» / «série», and the role
  name), listed in the PR body for the translation review.
- **Docs:**
  - the admin guide;
  - operations (series, assignment and its undo, grants, retiring, the split runbook);
  - one paragraph in the organizer guide;
  - what's new.

## 6. Authorization and audit

| Action | Root | Series admin (own series) | Admin (real row) of the series' recent newest contest | Others |
|---|---|---|---|---|
| Create, rename, retire series; assign or move contests | yes | — | — | — |
| Add series admins | yes | yes, own series, from its members (CA19; CA22 for grantees they added) | — | — |
| Re-grant an account root revoked | yes | — | — | — |
| Remove series admins | yes | — | — | — |
| Create a contest outside a series | yes | — | — | — |
| Create a contest in the series | yes | yes (CA16) | next year only (CA4) | — |
| Set a grant's start year | any year, at grant time and later | at grant time only, from their own start year to the current year + 1 (CA20) | — | — |
| Actions opted in with `derived = true` (§3.3) | all | derived Admin with year ≥ the grant's start year, Organizer below it (CA20), real rows on top, except Juror rows | own contest | — |
| Changes to or from Admin; adds, invitations, share and password links, change logs, account search, duplicates, account-kind jobs and joins | all | the series' contests with year ≥ the grant's start year, created before or after the grant (CA17), or a real Admin row held below it | own contest | — |
| Account rules | all | real rows plus `seriesAccountContests` (CA17), never protected accounts | real rows | — |
| Act on or set roles of a protected account | yes | — | — | — |
| Edit identity, set root, retire | yes | — | — | — |
| Reuse report, split, undo | yes | — | — | — |

**Audit:**

- **`contest_change`:** created, identity, assigned or unassigned (by batch), moved, split, undone,
  retired.
- **`account_change`:** grants and revocations (with the start year and its computed default),
  the adder, start-year changes (old and new values), people added from last year.
- **Log lines** on `security.roles` and `round.admin`.
- **Root's notices** on each creation and each grant added by a series admin.
- **Root's view** of each series' grants in the last 24 hours (CA22).

**Negative specs:**

- **Creation:**
  - a stale or non-newest contest's Admin can't create;
  - the inline check refuses;
  - a series admin gets a 403 outside their series, and can't create a year that is taken, out
    of bounds, or below their start year;
  - two concurrent creations of one series and year give one contest.
- **Series admins' limits:** they can't remove grants, assign contests, rename or retire a series,
  set root, or edit identities.
- **Account reach:**
  - **a series admin can act on the account of a member of a series contest with year ≥ the
    start year, whether the contest was created before or after the grant, but not on the
    account of a member of a contest below the start year, or of one who also belongs to a
    contest they don't administer;**
  - **a series admin can't act on a protected account** (root, another series admin);
  - **`seriesAccountContests` includes the series' live contests with year ≥ the start year
    created before the grant (and those with no `created_at`), and excludes those below it (a
    back-fill by root included), retired contests, a retired series and revoked grants; a root
    change of the start year changes it on the next request;**
  - **an invitation issued under a grant is accepted (`mayGrant`), and refused once the grant is
    revoked;**
  - **in a contest below the start year, a series admin without a real Admin row there can't
    add people, invite, make share or password links, change a role to or from Admin, read the
    change logs, search accounts, see duplicates, or start account-kind jobs or joins;**
  - **account search lists series admins (CA21), and an action on them is still refused;**
  - **`contestPermission` without `derived = true` refuses a derived role** (the opt-in guard
    spec);
  - **an API token and root's edit form get no series reach** (`withSeries` guard);
  - **a local Admin can't edit, link, invite, retire, join or set roles of a protected account;**
  - the guard specs on `seriesContests` and `derivedRoles`, the `derived = true` list, and bare
    target `isRoot` (all of `app/`).
- **Grants:**
  - a grant to a non-member, a deleted, retiring or root account, self, or an existing grantee
    is refused;
  - only root re-grants an account root revoked;
  - **a three-level chain** (root → A → B → C): a series admin adds at any depth; revoking A
    with the chain removes B and C; revoking B keeps A; each addition notifies root;
  - a join moves or revokes grants, keeps the start year, and retirement revokes them.
- **The start year (CA20):**
  - the default is the earliest year of the grantee's active real Admin rows in the series' live
    contests, capped at the current year − 1, else the current year − 1; rows moved by a J-2 join
    count, rows of an unjoined account, retired contests, inactive rows and Organizer rows don't;
  - a series admin can't set a grantee's start year below their own or above the current
    year + 1, and the default is raised to their own; root sets any year;
  - only root changes a start year after the grant; a series admin's attempt is refused; each
    change writes `account_change` with old and new values and takes effect on the next request.
- **Revocation and precedence:**
  - a revoked grant or a retired series takes effect on the next request and gives no
    protection;
  - a Juror row is never derived Admin;
  - a contest below the start year gives derived Organizer only (CA20); a real Admin row there
    gives Admin, and a real Organizer row from the start year on gives derived Admin (the higher
    role wins).
- **COI:** COI flags a series admin with no real row, but not one of a retired series.
- **International:** S2 and the check match any country.
- **Batches:** a batch undo restores the assignments.
- **Retired contests** give no access.
- **Split and Undo** refuse non-root.
- **CSRF** on every POST.

## 7. PR split

| PR | Content | Depends on | Migration | Size |
|---|---|---|---|---|
| Now (decided) | Root creates WLM 2026 in Nepal and adds the admin | — | — | minutes |
| **CY-0** | The reuse report; Q0–Q4 tuning. Built on `stack/212-series-cy0-report` (§4.3, numbers in §4.7) | D-1a, D-1b | — | M (3–4 days) |
| **CY-1** | Series table and key, derived previous, `contest_change` with batches, retired contests and series, `/admin/series`, proposals with bulk confirm, affected-grants list and batch undo, **the inline reuse check** (with every round's signals, R4), the menu grouped by series, the `/contests` filter, docs. Built on `stack/209-series-cy1` | D-1a | V109 | M–L (6–7 days) |
| Ops after CY-1 | **Production assignment:** Q0 and Q5 on the copy, then root's bulk confirmation on production, in batches | CY-1 | — | 1 day |
| **CY-2** | Series admins: `series_admin` with its live-grant key, CA19 adds with the grantee rules, root-only re-grant, **the chained revoke (required) with three-level specs**, root's 24-hour grants view, join and retirement hooks, `seriesAccountContests` in the account rules (CA17), `withSeries(user, currentContestId)`, `derivedRoles`, `contestPermission` real-only with the `derived = true` opt-in and `inContestReal`, CA20 derivation from `admin_from_year` with its computed default, bounds and root's later edit (logged), every action classified, `isProtectedTarget` replacing the 26 target checks, COI, the users-page line, the change-log view, the menu entry and landing, the grant UI with the start year and the contest list, guard specs, negatives; its own security review. Series admins' creation path and CA6's disabled grantees ship with whichever of CY-2 and CY-3 lands second. **Built on `feature/series-cy2-admins`** (2026-11-24): CY-2 lands first, so those two wait for CY-3 | CY-1 | next free at merge (V112 on its branch) | L (14–16 days) |
| **CY-3** | Creating contests: the series page, the next-year path (CA4), settings, the people picker, root's notices, the H1/import links. Creation by series admins (CA16) and CA6's disabled grantees ship with whichever of CY-2 and CY-3 lands second | CY-1 | — | M (5–6 days) |
| **CY-4** | Split, Undo, redirects, runbook; R4's per-cluster targets, memberships in the target (CA11, CA23), COI and regions from the target (§4.1); root's page and a job (CA13), Undo plus a dump (CA12), source links kept (CA10). **Decided** (CA9, 2026-11-20) on CY-0's numbers | CY-0, CY-1 | next free at merge (V111 on #124's branch) | L (9–11 days) |
| **CY-5** | The production split run | CY-4 | — | ops, 1–2 days |

**Order:**

1. CA8 asked; CY-1, once D-1a (and preferably D-1b) is in;
2. production assignment;
3. CY-2;
4. CY-3. Its Admin path needs only CY-1, so it may go before CY-2.
5. Series admins' creation path and CA6's disabled grantees: in whichever of CY-2 and CY-3 lands
   second.

CY-0 runs in parallel once D-1a and D-1b are in. CY-4 and CY-5 will run: the report's numbers
(§4.7) warranted them and CA9 decided it (2026-11-20).

**Effort:**

- CY-0 to CY-3: about **28–33 days**, plus the ops day (CA20's start year adds about a day to
  CY-2; dropping CA18's Take removes about one);
- CY-4 and CY-5: **9–12** more.

### Pilot (user testing)

Directed by the maintainer, 2026-11-18.

**First pilot:** a WLM UA admin, named by the maintainer when CY-2 is ready, as the first series
admin of the "Wiki Loves Monuments in Ukraine" series.

**Steps:**

1. **After CY-1 and the production assignment:** root reviews the proposed WLM UA series on the
   production copy, then on production, and confirms it.
2. **After CY-2:** on the copy at :9100, root grants the pilot "Series admin", with the default
   start year being the pilot's earliest real Admin year in WLM UA. The pilot checks:
   - the contest switcher's grouping;
   - Admin from the start year on, read-only before it;
   - no reach over root or other series admins;
   - the change log and root's notices.

   Then the same on production at a quiet time. **Back-out:** revoke the grant.
3. **After CY-3:** the pilot creates "WLM 2027 in Ukraine" from the series page with the people
   picker, on the copy first, then on production when it is due.

**Feedback** from each step goes into the next PR, or into a fix before the next step.

**Second pilot:** the Nepal admin (International, a previously reused contest). It exercises the
reuse report (CY-0) and a series created from scratch.

## 8. Risks

- **A reader over-reads the series.** Mitigations:
  - `withSeries` is called only for sessions, with a guard spec;
  - `seriesContests` has an allowlist of readers;
  - the per-action classification;
  - `isProtectedTarget` with its own guard spec;
  - CY-2's security review.
- **A compromised or mistaken series-admin account (CA17, CA22, accepted).**
  - **What it can do:**
    - act on the accounts of everyone in the series' contests from its start year on (edit,
      links, invitations), **including contests that existed before the grant**, except
      protected accounts (CA17 re-decided 2026-11-18; before, only contests created since the
      grant);
    - act as Admin on rounds and settings in every contest from its start year on;
    - add a chain of further series admins at any depth, with start years no earlier than its
      own.
  - **Mitigations:**
    - the default start year: the grantee's earliest real Admin year in the series, else last
      year, so by default reach covers only years the account already administered, or last
      year on;
    - only root changes a start year after the grant, logged with old and new values;
    - the granter's bounds: a series admin can't set a grantee's start year below their own, so
      a chain never widens reach;
    - root's in-app notice on each grant, root's 24-hour grants view per series, and root's
      chained revoke, which removes a whole branch;
    - every change logged in `account_change`;
    - grantees must already be members of the series' contests;
    - protected targets (root, other series admins) stay out of reach, and the `forall` rule
      keeps members of contests the series admin doesn't administer out of reach.
- **A start year set too early (CA20).** Derived Admin, and with it account reach (CA17), then
  covers many past contests (rounds, settings, imports, their members' accounts), for example
  after joined accounts bring an old Admin row. Mitigations:
  - the default is capped at last year and comes only from the grantee's own real Admin rows;
  - the granter sees the full contest list by role before confirming, with the note on account
    reach;
  - a series admin can't go below their own start year, so a chain never widens it;
  - root edits it at any time, logged with old and new values.
- **Series admins are visible (CA21, accepted).** Account search shows them, so others can see who
  they are. No action on them is possible without root.
- **A wrong bulk assignment.** Mitigations:
  - proposals only;
  - the affected-grants list on confirmation;
  - CA20 and CA17 limit derived Admin and account reach to contests from each grant's start
    year on, and the confirmation shows which; an old contest assigned below every grant's start
    year adds neither;
  - batch undo.
- **The inline check misses a reused contest.** Mitigations: the staleness bound and root's
  notices. Series admins are trusted per series.
- **A mis-split.** Mitigations:
  - the dry run, the hash, the per-contest confirmation, Undo and the dump;
  - R3 is never moved;
  - only matching files are linked.
- **"Series" read as Montage's Series.** Mitigation: help text.
- **Multi-role landing later.** The precedence rule gets revisited.

## 9. Not determined

(The count of reused contests, 466's row and Q1's bands were determined by the CY-0 report, §4.2 and
§4.7.)

- Production's country values (Q5).
- How many contests the proposals can't group.
- Whether multi-role lands before CY-2.
- How many files of national contests are unclassified.
- The community terms (the decided "Series" stays).

## Decisions for the maintainer

**Decided (2026-11-18):**

- **CA1:** the Series entity.
- **CA2:** "Series" / «серія» / «серия» / «série».
- **CA14:** Nepal now. 1974 and 1975 stay in 466, and 466 stays unassigned.
- **CA19:** series admins add, but don't remove, series admins of their own series. Each
  addition is logged and notified to root. Only root removes. The grantee is protected. The risk
  is recorded.
- **CA4, CA6, CA15, CA16:** the first batch below, each decided as recommended.
- **CA20 reworked:** one start year per grant (second batch below), replacing the fixed window.
- **CA17 re-decided, CA18 dropped:** the start year is the only rule; the grant date no longer
  matters (second batch below).

**Defaults from decided rules** (not asked):

- **CA7:** the series doesn't apply where the series admin holds a Juror row. Those contests are
  listed at the grant, and the grant is never refused for juror history (O14).
- **CA5 (scope shapes):** obsolete with series. A grant is per series; a person over all of WLE
  gets one grant per series.

### First batch: decided 2026-11-18 (each as recommended)

- **CA4: Which contest Admins (not series admins) may create the series' next year.**
  - [chosen] **Only an Admin of the series' most recent contest, and only if all three
    hold:**
    - that contest is from this year or last year;
    - the new contest is its year + 1, at most next year;
    - the app finds no sign of reuse in it: no import from another type, country or year, and
      its round ids don't span several seasons.

    Otherwise they ask root or a series admin. Root gets an in-app notice of each creation and
    may reassign the new contest's Admins or retire it.
  - **Series admins and root only.** Clear, but a series without an admin waits for root.
  - **An Admin of any contest of the series, any later year.** This is the workaround's
    population: a past team could claim new years.
- **CA6: Copying people into the new contest.**
  - [chosen] **A picker of the previous year's members:**
    - nothing ticked;
    - series admins shown but disabled (they already have access);
    - the invitation email box unticked;
    - an in-app notice to each person added.
  - **Jurors pre-ticked.** Faster, but it adds people by default.
  - **No copy.** The admin re-adds people by search.
- **CA15: Two contests of one series and year.**
  - [chosen] **One live contest per series and year, enforced from the first migration by
    a database key** (`UNIQUE (live_series_id, year)`; retired and unassigned contests don't
    count).
    - A lock on the series gives a clear message.
    - Assignment refuses a second contest of the same year: it goes into its own series, or root
      resolves it.
    - Outside any series, root's creations only get a warning for an existing name, year and
      country. This is a change from the earlier "refused everywhere".
  - **An app check only, with the key added later.**
- **CA16: Which years a series admin may create.**
  - [chosen] **A year not yet taken, from last year to next year.**
    - **With the reworked CA20:** not below the series admin's own start year (the creator gets
      a real Admin row). With the default start year (at most last year) this never bites. CA4 is
      unaffected: it concerns real Admin rows and keeps its own staleness rule.
    - Contest Admins follow CA4.
    - Root may create any year.
    - Why not older past years: back-filling old years is rare, and root can do it. Keeping it
      root's stops series admins from reshaping a series' history. The "previous year" is
      derived, so allowing more later costs nothing.
  - **Any year not taken, up to next year, past years included.**
  - **Only this year and next year.**

### Before CY-1: decided 2026-11-18

- **CA8: Audit storage.** P19 is the admin-action audit log of the rounds-parity plan (row 15,
  security register P19): planned, not designed.
  - [chosen] **A small change log now:**
    - grants, revokes and start-year changes go into `account_change`, with the new fields
      (Takes too, until CA18 was dropped);
    - a small `contest_change` table records contest creation, series assignment and identity
      edits (and, later, splits and retirement), which P19 reads later.
  - Log lines only until P19 exists.

### Second batch: decided 2026-11-18

- **CA3: The role's name.**
  - [chosen] **"Series admin"** (uk «адміністратор серії», ru «администратор серии», fr
    «administrateur de la série»).
  - "Admin of the series {name}".
  - "Coordinator": a Montage false friend.
  - "National organizer": collides with Organizer.
- **CA17: Does a grant reach accounts? Re-decided 2026-11-18.**
  - The recommendation was "No: real rows only".
  - [chosen, re-decided] **Reach from the start year on.** In the series' live contests with
    year ≥ the grant's start year (CA20), the series admin is a full contest Admin: the grant
    counts for the account rules as a real Admin row would, including invitations and share
    links (`mayGrant`, `adminContests`), through `seriesAccountContests`, whether the contest was
    created before or after the grant. Below the start year it gives no account reach (derived
    Organizer). Protected accounts stay out of reach. `mayGrant` and `adminContests` accept any
    live grant.
    - **Risk accepted:** a compromised or mistaken series admin reaches the accounts of everyone
      in the series' contests from the start year on, including contests older than the grant.
      Mitigations in §8: the default start year, root-only later changes, the granter's bounds,
      root's notices, the 24-hour view and the chained revoke, and protected targets.
  - [earlier versions, decided and replaced the same day] **"Reach in newer contests":** only
    the series' contests created since the grant; then, reconciled with the reworked CA20,
    contests created since the grant **and** with year ≥ the start year.
  - Count all the series' contests regardless of the start year: the takeover path, rejected.
- **CA18: How a series admin gets a real Admin row. Dropped 2026-11-18.**
  - With CA17 re-decided, the grant already gives everything a real Admin row would from the
    start year on, and below it Take was refused, so no purpose remains. The action, its notice
    to root and its specs are removed; below the start year root adds a real row or lowers the
    start year.
  - [earlier versions, decided and replaced the same day] **"Take the Admin role here"**, logged,
    with an in-app notice to root, and a local Admin couldn't remove or downgrade it: first "for
    contests within CA20's window that are older than the grant", then for contests with year ≥
    the start year created before the grant.
- **CA20: How far derived Admin reaches back. Reworked and decided 2026-11-18.**
  - [chosen, reworked] **One start year per grant** (`series_admin.admin_from_year`): derived
    Admin in the series' contests with year ≥ it, derived Organizer (read-only) in older ones.
    - **Default,** computed at grant time, shown and editable by the granter: the earliest year
      of the grantee's own active real Admin rows in the series' live contests (rows moved onto
      the account by J-2 joins count), capped at last year; with none, last year (the current
      year − 1).
    - **Why:** accounts used to be separate per year and get joined later, so past real Admin
      roles move onto one account and should count.
    - **Bounds:** a series admin who grants may set from their own start year to the current
      year + 1; root may set any year.
    - **Later:** only root changes it, each change logged in `account_change` with old and new
      values.
    - **Real roles** add on top and the higher role wins; no separate window rule, which removes
      the two overlapping concepts (window and grant date) of the earlier version.
  - [earlier version, decided and replaced the same day] Derived Admin for year ≥ the current
    year − 1 and for contests created since the grant; older contests read-only (derived
    Organizer).
  - Derived Admin in every contest of the series.
  - Derived Organizer everywhere.
- **CA21: Does account search hide series admins as it hides root?**
  - The recommendation was "Yes".
  - [chosen] **"Show them."** Account search lists series admins like anyone else, and
    `rootShown` stays for root only. The protection still blocks role and account changes by
    non-root admins. Residual risk: others can see who the series admins are.
- **CA22: May a series admin added by another series admin add further?**
  - The recommendation was "No".
  - [chosen] **"Yes, any depth."** Root's chained revoke along `granted_by` removes a whole
    branch. It is required in CY-2, with specs for a three-level chain. Root sees each series'
    grants of the last 24 hours on `/admin/series`.

### Third batch: after Q0–Q4 and the CY-0 report — decided 2026-11-20 (each as recommended)

- **CA9: The approach.**
  - [chosen] The report, then per contest: move clean clusters into new contests of the
    right series and year, leave R3, or start clean.
  - Leave all history (this drops CY-4).
  - Move automatically.
- **CA10: The source's file links.**
  - [chosen] Keep them.
  - Remove the unused ones.
- **CA11: Memberships on a move.**
  - [chosen] The Juror role for the moved jurors only, skipping deleted accounts, O14
    conflicts and protected accounts, and listing the rest; their old memberships stay.
  - Copy all members.
- **CA12: The way back.**
  - [chosen] Undo plus a dump (before the first production run). This departs from O1 because the
    split runs online.
  - A dump restore only.
- **CA13: Where the split runs.**
  - [chosen] Root's page and a job.
  - A CLI step.
  - Series admins (later).
- **CA24: decided 2026-11-20** (as recommended): the word after "Wiki Loves" as the type code
  when scalawiki doesn't know the type. See the decisions at the top.
- **CA23: The Admin role in an R4 cluster's target** (raised by the 2026-11-20 refinement, §4.1).
  CA11 covers the moved jurors; the people who ran the cluster's rounds in the host contest are
  usually that country's own team.
  - [chosen] List the known ones on the confirmation with none pre-ticked (multi-role decision 7,
    CA6); root ticks who gets Admin in the target, and may add others; in-app notices, no mail.
    (On the production copy only 1 of the 67 clusters to move has a known runner, §4.7.)
  - Give each of them Admin in the target automatically.
  - No Admin rows: root or a series admin adds them afterwards.

## Appendix: queries for the production copy (read-only)

```sql
-- Q0: duplicate identities (assignment, CA15)
SELECT name, year, country, COUNT(*) n, GROUP_CONCAT(id) ids
FROM contest_jury GROUP BY name, year, country HAVING n > 1;

-- Q5: country values (the proposals' International rule)
SELECT country, COUNT(*) FROM contest_jury GROUP BY country ORDER BY 2 DESC;

-- Q3: the quick screen: contests whose rounds span far in id (150 is a first guess)
SELECT c.id, c.name, c.year, c.country, COUNT(*) rounds, MIN(r.id), MAX(r.id), MAX(r.id) - MIN(r.id) span
FROM contest_jury c JOIN round r ON r.contest_id = c.id
GROUP BY c.id HAVING span > 150 ORDER BY span DESC;

-- Q1: page-id bands per contest year, from contests Q3 does not flag (S1 uses min/max)
SELECT c.year, MIN(ri.page_id) min_page, MAX(ri.page_id) max_page, COUNT(*) files
FROM contest_jury c JOIN round r ON r.contest_id = c.id JOIN round_image ri ON ri.round_id = r.id
WHERE c.id NOT IN (SELECT r2.contest_id FROM round r2 GROUP BY r2.contest_id
                   HAVING MAX(r2.id) - MIN(r2.id) > 150)
GROUP BY c.year ORDER BY c.year;

-- Q2: per round, size and page-id range, to place each round in Q1's bands
SELECT c.id, c.name, c.year, c.country, r.id round_id, r.number, COUNT(ri.page_id) files,
       MIN(ri.page_id), MAX(ri.page_id)
FROM contest_jury c JOIN round r ON r.contest_id = c.id LEFT JOIN round_image ri ON ri.round_id = r.id
GROUP BY c.id, r.id ORDER BY c.id, r.id;

-- Q4: other years named in the categories of a contest's files (heavy: the copy only)
SELECT c.id, c.name, c.year, c.country, REGEXP_SUBSTR(cat.title, '20[0-9]{2}') y, COUNT(*) links
FROM contest_jury c
JOIN category_members own ON own.category_id = c.category_id
JOIN category_members cm  ON cm.page_id = own.page_id
JOIN category cat         ON cat.id = cm.category_id
WHERE cat.title LIKE 'Category:%'
  AND REGEXP_SUBSTR(cat.title, '20[0-9]{2}') <> ''
  AND REGEXP_SUBSTR(cat.title, '20[0-9]{2}') <> c.year
GROUP BY c.id, y HAVING links > 20 ORDER BY c.id, y;
```
