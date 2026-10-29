# Conflict of interest filter

A contest's rules usually say that photos by the people who run or judge it can't win:
organizers, jurors, the board and the audit committee of the NGO running the contest, and its
staff. Today an organizer finds those files by hand and puts them into a Commons category that a
round's "files should NOT be in the category" filter leaves out (WJ-F5.7 in
`docs/comparison/01-wlxjury.md`). This plan stores each file's author as scalawiki determines it,
matches it with the wiki accounts of the contest's members in chosen roles (with new roles for
the board, audit committee and staff), and leaves their files out of rounds,
with the reason visible to organizers.

## What exists

- **Round filters.** A round is filled by `RoundDistributionHandler` in chunks; each chunk passes
  `DistributeImages.perImageFilters`, the chain of `ImageWithRatingSeqFilter` generators (regions,
  monuments, include/exclude page ids from Commons categories, megapixels, size, media type,
  previous-round gates). Top-N ranks after the per-image filters. A pasted list (phase `List`)
  adds files "whatever the round's filters".
- **What wlxjury stores about a file's author.** `images.author` is the raw wikitext of
  `{{Information}}|author=` (`ImageText.withText`), read only for contests whose import fetches
  the page text (those with a monument id template); the large view shows it through
  `Image.parsedAuthor` (scalawiki's old `parseUser`). The uploader is not stored: the import asks
  for `iiprop=user` (the latest version's) and `ImageJdbc.fromPage` drops it.
- **Author detection in scalawiki `master`** (14a4362e, 61f063d6, 2026-10-20):
  - `Image.fromPageRevision`: the author from `{{Information}}` (`author`/`Author`), else
    uk.wikipedia's `{{Зображення}}` (`Автор`…); none when the page names none or says `Я` ("me").
  - `Image.parseUser`: the first user linked in any user namespace or project
    (`[[User:X|…]]`, `[[:uk:Користувач:X]]`, `{{User:X/author}}`) or by a user template
    (`{{U|X}}`, `{{original uploader|X}}`), else the text without markup; `{{unknown|author}}` and
    the like give nobody.
  - `ImageQueryApi.withCreditTemplateAuthors`: a photographer's credit template as the whole
    author (`{{Yuriy Kvach}}`) is credited to the user its page links to (one request per 50
    templates).
  - `ImageQueryApi.withOriginalUploaders`: a file with no author is credited to its **original**
    uploader, from `ImageInfo(IiProp(IiUser), IiLimit("max"))` per 50 page ids, the last
    (oldest) version's user; never a bot (`Image.authorFromUploader`).
  - wlxjury builds with `origin/page-categories` (master plus page categories and
    `redirects`), which has all of this; `target/dep-sources` (extracted 2026-10-13) is older,
    run `sbt extractDepSources` again. The two `ImageQueryApi` steps are private and run on
    scalawiki's bot, not wlxjury's `MwApi` (retries, the logged-in session, `toomanyvalues`
    splitting).
- **Re-imports update stored files.** `ImageImportHandler` inserts new files and updates stored
  ones whose fields changed (`ImageJdbc.updateIn`, compared in `ImageService`), so a new column
  is filled for existing contests by importing them again.
- **Accounts.** `users.wiki_account` is free text set by an admin, checked against Commons in
  `UserController.wikiAccountInfo`; OAuth login (which would make it reliable) is still on
  `ukwikidev/oauth-login`. Contest roles are in `user_contest` (admin, organizer, jury).
- **Votes moved aside.** Votes of files deleted on Commons move to `selection_deleted` (V63) and
  come back when an import finds the file again (`DeletedFiles.restore`, by page id).

## What Montage does

`CoordinatorDAO.autodisqualify_by_uploader` (`montage/rdb.py:1608-1663` at `origin/master`
6bcb6ea, unchanged since the comparison's `f9ddfbd`):

- **Who:** the round's jurors always; the campaign's coordinators (`dq_coords`); **every**
  organizer of the tool, of any campaign (`dq_organizers`); the hard-coded `MAINTAINERS`
  (`dq_maintainers`). There is no way to name the board, auditors or staff.
- **Which files:** `entries.upload_user_text IN (...)`, an exact match on the uploader of the
  earliest file revision (current `labs.py`). The author field is not read, so a staff member's
  batch upload of other people's photos is disqualified, and a board member's photo uploaded by
  someone else is not.
- **Effect:** `round_entries.dq_reason` gets a free-text reason ("upload user X is juror"),
  `dq_user_id` the coordinator, and the entry's votes are set to `cancelled`. Runs after every
  import when `dq_by_uploader` is set (default **off**) and on
  `POST /admin/round/<id>/autodisqualify`; `preview_disqualification` lists matches without
  writing; `requalify` restores the votes. Only the post-import DQ list has a UI (MT-F5.6).

What to take from it: the attribute stored with the file, rules that apply again to new files,
a preview with reasons. What to do differently: the author rather than the uploader; the
contest's own members by role instead of "all organizers of the tool"; votes deleted rather
than cancelled and kept for a requalify (the rates of these files are not needed); names
normalized as MediaWiki
normalizes them (Montage's import check needed `f9a9313` for Georgian title case); a UI for the
preview.

## The feature

### 1. Store the author as scalawiki determines it, in `images.author`

`images.author` holds the author as credited instead of the raw wikitext: the linked user's
name, else the author text without markup, else the original uploader (not a bot); none for an
unknown author. Normalized as MediaWiki normalizes user names (`_` → space, first letter
title-cased) so it compares with the list. No new column: the raw wikitext is only ever shown
parsed (`Image.parsedAuthor` in the large view and the organizers' file list;
`GalleryController` only checks it is non-empty), and an import fetches it again if the parsing
changes. `parsedAuthor` goes; the views show `author`.

- **Rows imported before:** they keep the raw wikitext until the contest is imported again.
  Until then the round filter and the preview read the author through `Image.parseUser`, which
  leaves an already-resolved name as it is, so linked users in old rows match too; what old
  rows lack is the original-uploader fallback (files with no author) and credit templates.
  Deploy step: import the running contests again (WLM 2026), which fills both.
- **Length:** the import keeps 256 characters (`take(256)`) of a `varchar(255)` column, which
  strict mode refuses for a 256-character author; keep 255.

- **scalawiki: no change for now.** The pure parts are public in the published snapshot
  (2026-10-28: `Image.fromPageRevision`, `parseUser`, `creditTemplate`, `userIn`,
  `authorFromUploader`, `IiLimit`); wlxjury's `CommonsSource` sends the two requests
  (`ImageInfo(IiProp(IiUser), IiLimit("max"))` per 50 page ids, and the credit templates' text
  per 50 titles), as `ImageQueryApi.withOriginalUploaders` / `withCreditTemplateAuthors` do.
  Moving the request shapes and their readers into scalawiki core, shared by both, can follow.
- **wlxjury import:** `ImageText.withText` takes the author from `Image.fromPageRevision` +
  `parseUser`. Then, for the chunk's files, two more requests through `CommonsSource` (wlxjury's
  retries and session): credit templates' user pages, and the upload history of the files left
  without an author. Both are usually empty or a request per chunk; an import of files
  with `{{Information}}|author=[[User:…]]` (UploadWizard's own work) makes neither.
- **Text for every contest:** the author needs the page text, which the import fetches today only
  with a monument id template. Fetch it for every contest (`ImageText.images`), or at least for a
  contest with a conflict-of-interest list (see "Decisions").
- Existing contests get the resolved author by importing again; no separate backfill. Today a
  re-import never updates a stored author: `ImageService.changedImages` compares and
  `ImageJdbc.updateIn` writes the file info, monument id and description only. Add `author` to
  both, kept as stored when the import fetched no text, as 4f6b0cf3 keeps the monument id and
  description of a shared file.

### 2. Who: the contest's members, by role

No list of its own: the people are the contest's members (`user_contest`), and their
`users.wiki_account` is the name to match.

- **New contest roles without permissions:** `board`, `auditor` (audit committee) and `staff`,
  next to `admin`, `organizer` and `jury` (`user_contest.role` is free text; `prejury` exists
  already). Added on the contest's users page or by "Import users" as jurors are today: a
  `User:Name` line makes a wiki-account-only user (`User.parseList`), and an account that exists
  already is found by its email or wiki account (`UserService.existingAccount`) and gets a
  membership, so next year's contest reuses the same users. The import form gets a role select
  (it adds `jury` only today).
- **Which roles count:** hardcoded for now, `User.COI_ROLES = Set(admin, organizer, jury,
  board, auditor, staff)`: every contest role except `prejury` and `no`; and **root**, which is
  not a contest role (`users.is_root`, no `user_contest` row needed): every root user is a
  conflict of interest in every contest, as Montage's `dq_maintainers` does. A contest setting per
  role can follow if a contest's rules allow, say, organizers' photos; until then such a
  contest unticks the round's checkbox (section 4).
- **One role per member per contest** (`PRIMARY KEY (user_id, contest_id)`): a board member who
  is also a juror is a juror there, and is a conflict of interest either way.
- `CoiMembers.forContest(contestId): Map[String, (role, userId)]`: one query of the members in
  `COI_ROLES` and the root users (`UNION`, not deleted), by normalized `wiki_account`; a root
  user who is also a member is reported with their contest role. A file is a conflict of
  interest when its `author` is a key; the reason is the user and their role (or `root`). Nothing is precomputed, so a membership
  change applies at once.
- **The users page** marks, for organizers and admins, a member in `COI_ROLES` without a wiki
  account: "no wiki account, can't be checked for conflicts of interest". Which files are out
  and why is the contest files page's filter (section 3), root users' included.

What roles need that a separate list wouldn't:

- **No access from these roles.** They are in no permission set, so `LoginController` finds no
  start page for them, but every page must refuse them: negative tests per controller (the
  comparison found the large view checking only the default viewer role, WJ-F2.4). They get
  no greeting mail (`UserService.addToContest` sends it when `contest.greeting.use`) and no
  password mail.
- **Wiki-account-only users:** `users.email` is `NOT NULL` and `UNIQUE`, and `parseList` gives
  such a user `email = ""`. Check whether a second one can be stored today (it matters for
  jurors imported by account too); if not, store `NULL` for no email (a migration making the
  column nullable; a unique key allows many `NULL`s).
- **One account per user:** an alternative account or a former name before a rename needs
  another user. Enough for now; an "other accounts" field can follow if it comes up.
- **Juror lists stay jurors:** round juror pickers and distribution read `jury` members
  (`User.loadJurors`, `distributionJurors`), so the new roles don't appear there; the users
  page shows them with their role.

```sql
ALTER TABLE rounds ADD COLUMN exclude_coi BOOLEAN NOT NULL DEFAULT FALSE;
```

MariaDB 10.6 syntax, nothing that needs 10.8 or later.

### 3. Preview

- The contest files page (`GET /contest/:contestId/files`, `GalleryController.contestFiles`)
  gets a `coi` parameter next to `deleted`, `round`, `region` and `country`, with a menu entry
  "Conflicts of interest" (organizers and admins only): the contest's images whose `author`
  matches, each labelled with the person and the reason ("X — board", "Y — root").
- The round form shows, with the new checkbox, "N of the contest's files are conflicts of
  interest", and "M files have no author stored: import the contest again" while a contest
  imported before this change has files not imported again since.

### 4. Leaving them out of a round

- The round form gets **"Leave out conflicts of interest"** (`rounds.exclude_coi`), checked by
  default for a new round. Per round, as wlxjury's other eligibility rules are, so a special
  nomination for staff photos, or a contest whose rules allow organizers' photos, stays
  possible.
- `RoundDistributionHandler.context` reads `CoiMembers.forContest`, and `perImageFilters` adds
  an `ExcludeAuthors(names)` generator on `image.author` (through `Image.parseUser` for rows
  not imported again). So it leaves out the files from a first round, a round from previous
  rounds (catching members added after round 1), top-N (ranked
  after the filters, so the top 10 is ten eligible files), and "Distribute new files". No
  Commons request.
- A pasted list: the listed files that are conflicts of interest are left out too and reported
  in the job's details ("left out as conflicts of interest: …"), as missing titles are. The list
  phase's freshly fetched files get their author the same way as an import's. An organizer who
  really wants them unticks the round's checkbox.

### 5. A round that already has the files (second step)

For rounds created before this, or after a member was added: "Leave out conflicts of
interest now" on the round page, for open rounds that aren't distributing.

- Deletes the files from the round with their votes: the rates of a conflict-of-interest file
  are not kept. `SelectionJdbc.removeImage(pageId, roundId)` does this already (no route today,
  WJ-F6.6): the round's `selection` rows and those moved aside to `selection_deleted`, so
  restoring the file on Commons doesn't bring them back, then `RoundImageCounts.invalidate`.
  Add a batch form for the round's files, in one transaction with the round locked
  (`lockRounds`), that also deletes their `criteria_rate` rows, which reference `selection.id`
  without a foreign key (V53 had to clean orphans by hand).
- One log line per removal (round, page ids, the members and roles, who), since nothing is
  kept to show it later, on a logger logback writes: INFO of class-named loggers is not written
  today (both `logback.xml` files raise only `play`/`application` above the root's WARN, WJ-Q6
  in the comparison), so a named logger (`coi`) set to INFO in both files.
- No requalify: a file removed by mistake (a wrong role, a mismatched author) comes back by
  fixing that and "Distribute new files", which adds it as a file not in the round.

## Authorization

- Add members with the new roles: whoever adds members today (the users page and "Import
  users", `contestPermission(ADMIN_ROLES)`). Step 5's removal: the contest's admins. The files filter and the users page's
  marks: admins and organizers.
  Jurors never: it tells which files are out.
- The new roles grant nothing: every page refuses a member whose only role in the contest is
  `board`, `auditor` or `staff`, as it refuses a non-member.
- A round's checkbox and the exclusion act on a round of the current contest; the round's
  contest is checked with `Secured.inContest`, never by switching on a POST.
- All forms are POSTs with the CSRF token.

## Alternatives

- **The uploader instead of the author** (Montage): one column from the image info the import
  already asks for, no page text. But a staff member's upload of other people's photos would be
  excluded and a board member's photo uploaded by a friend would not; the latest version's user
  (what the import gets without `iilimit`) is also wrong after a bot's rotation or someone's
  crop. scalawiki falls back to the original uploader only where the page names no author.
- **Both author and uploader:** matching either catches a staff upload credited to a made-up
  name, at the cost of the false positives above; could be a per-round option later.
- **The upload log per account** (`list=logevents&leaction=upload/upload&leuser=…`): needs a scan
  job and a table of results that goes stale after each import and membership change.
- **Montage's model: disqualify after import, cancel votes.** wlxjury has no per-file round
  state; leaving files out at distribution needs none, and step 5 covers rounds already filled.
- **Only "don't give jurors their own files"** (reassign to the next juror). Doesn't cover board
  and staff, and with `distribution = 0` changes the per-image denominator. Could be added to
  `DistributeImages.assign` later for contests whose rules allow organizers' photos.
- **A separate list of accounts per contest** (`coi_account`: name, group, note): several
  accounts and former names per person, no user rows for people who never log in, and nothing
  to secure about new roles. But it duplicates the users page and `wiki_account` checks, and
  needs its own "copy from another contest"; members already carry everything but alternative
  accounts.
- **Roles chosen per contest, real names** (later): a checkbox per role in the contest's
  settings, for rules that allow organizers' photos; matching a plain-text author with no user
  link against members' `fullname` (exact, case and spaces normalized; off by default, common
  names collide).
- **A Commons category** (today's workaround): keeps working; the roles replace the hand work.

## Decisions to confirm

1. The import fetches the page text for every contest (proposed), not only those with a monument
   id template.
2. Pasted lists obey the round's checkbox (proposed) rather than bypassing it like other filters.
3. The author decides, not the uploader (see Alternatives).
4. Members, by role, rather than a separate list; one account per person for now; the roles
   that count hardcoded.

## Tests

- `CommonsSource` (a stub bot or recorded answers): the original uploader is the oldest version's
  user across `iicontinue`; a credit template's page gives its user.
- Import (DB, jobs inline, a stub `CommonsSource`): the stored `author` from a `[[User:X|…]]` link, a
  user template, `{{Зображення}}`, a credit template (its page's user), plain text; the original
  uploader for no author and for `Я`, not a bot, nobody for `{{unknown|author}}`; a re-import
  fills it for a stored file.
- `CoiMembersSpec` (DB): normalization of `wiki_account` (`User:` prefix, `_`, lowercase first
  letter, Georgian and other title case as MediaWiki); members of `COI_ROLES` only (a
  `prejury` member not); a root user counted in a contest they aren't a member of, a deleted
  root user not, a root member reported with their contest role; an inactive or deleted member
  not counted; "Import users" with a role,
  two wiki-account-only users in one import.
- `RoundDistribution` specs: `exclude_coi` leaves the files out of a first round, a
  previous-rounds round and top-N (ranked after); a pasted list reports them; without the flag
  they stay; the count check passes.
- Step 5: the files' votes, votes moved aside and criteria rates are gone, other files' are
  untouched; the round's counts change; `DeletedFiles.restore` brings nothing back; refused while
  the round is distributing or closed; after the member's role is removed, "Distribute new
  files" adds the file again.
- Authorization, negative cases first: a `board`/`auditor`/`staff` member signed in gets no
  start page and a 403 on every page (galleries, large view, round and contest pages, users,
  files); a juror and an organizer removing a round's files (403); an admin of another
  contest; a juror opening the contest files filter; root allowed. No greeting mail for the
  new roles. CSRF through `CsrfTestApp.withCsrfApp`.

## Size

Steps 1–4: about three days (the two Commons lookups half a day, the import's author a day,
the new roles and the import's role select half a day, the round filter, preview and the new
roles' authorization tests a day). Step 5: about half a day. Afterwards update WJ-F4.9, WJ-F5.7,
WJ-F6.2 and the comparison's eligibility rows.
