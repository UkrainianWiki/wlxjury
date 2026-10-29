# Import the top N of another contest's round

The international contest (Wiki Loves Earth 2026 in International, contest 630) takes each
country's winners. Countries that judged in the jury tool already ranked them in a round of
their own contest: Ukraine's top 10 is the top of a round of the Ukrainian contest. Today an
organizer copies that round's file list, ranked by rating, and pastes the titles into "Import
files". This adds the round as a source: "the top N of round R of contest C".

## What exists

- **Top N within a contest.** A round's `previous` + `topImages` filters take the top N of a
  previous round, ranked in SQL by `DistributionSource.rank` (the average rating over the
  denominator, ties by the rate sum, then the raw sum, then page id; per-image filters apply
  before ranking). The previous rounds are looked up in the round's own contest only
  (`RoundDistributionHandler.previousRounds` → `Round.findByIds(round.contestId, ids)`).
- **Pasted lists.** "Import files" (`ImageImportHandler`, titles in the job's params) adds the
  listed files to the contest; a round's file list (`RoundDistributionHandler`, phase `List`)
  adds them to one round only, not to the contest. Both look titles up with redirects resolved,
  and the import gives files their countries for a contest of many (`ContestCountries`).
- **Authorization.** "Import files" is for the contest's admins (`contestPermission(ADMIN_ROLES)`).
  Another contest's round is checked as the user in that contest (`Secured.inContest`, active
  memberships only); `User.canViewOrgInfo(round)` is who may see its ratings (root, its contest's
  admins and organizers, its jurors when `juryOrgView`).

## The feature

On the "Import files" page, a third source next to "category or page" and "list":
**"Top of a round"**: a contest, a round of it, and N (default 10).

1. **Choosing the round.** A select of the rounds the user may rank: of the contests where the
   user is an active admin or organizer (root: all), the rounds with votes, labelled
   "contest — round" and the round's type (binary / rated). Read when the page opens; contests
   are few.
2. **Ranking.** The same order as the source round's results and its "top N" filter:
   `DistributionSource.rank(Seq(roundId), rated, denominator, n, identity)`. Moving `rank` and the
   `Denominator` choice (`RoundDistributionHandler` builds it from the round) to one place both
   use keeps the two from drifting. Ties at place N: the ranking breaks them (rate sum, page id),
   so exactly N files come; the import's details list the rating of the Nth and of the first
   left out, so an organizer sees a tie and can add the next one by a list.
3. **Import.** A new `ImportParams` field `fromRound: Option[FromRound(roundId, n)]`, fixed at
   creation. The job's first (and, for N ≤ the batch size, only) step: rank, then the ranked
   files' images from the database (they are stored: the source contest has them), their
   categories from Commons for the countries (a contest of many, as a pasted list), and the
   write as today (`linkIgnore` into the contest's category, `addCountries`). No image info
   request: the stored images are current enough, and the round's files may since be deleted
   on Commons (`deleted_at`): those are left out and reported, as a list's missing titles are.
4. **What it records.** The job's title "Importing the top 10 of «WLE 2026 in Ukraine — Round 2»",
   its details the ranked titles with their ratings; the contest's source (`images`) stays as it
   is, as a pasted list leaves it.

## Authorization

- The target: the contest's admins, as "Import files" today.
- The source: checked at submit, never trusted from the form: the user must be root, or an active
  admin or organizer of the source round's contest (`Secured.inContest(user, round.contestId)`
  then `canViewOrgInfo(round)` without the juror case: a juror of the Ukrainian round who is an
  admin of the international contest must not import its ratings). A round that doesn't exist or
  that the user may not rank answers the same "not found", so round ids can't be probed.
- The job runs with the params checked at creation; the step doesn't re-check (as today's
  imports).

## Alternatives

- **Into a round, not the contest:** "the top N of another contest's round" as a round's
  file list source (`RoundDistributionHandler` phase `List`, ranked ids instead of titles). The
  files then stay out of the contest's files page and its country menu; useful only if a round
  must hold files the contest doesn't. Can follow on the same ranking.
- **Cross-contest `previous` round:** let a round's previous rounds be another contest's. It mixes
  two contests' juries, freezing (`Round.freeze`) and statistics; much larger, not needed for
  winners lists.
- **Keep copying the file list:** works today; error-prone (ties, the rank column, renamed files).

## Tests

- `ImportFromRoundSpec` (DB, jobs inline): the top N of a rated and of a binary round, in the
  results' order; ties broken as `rank`; files deleted on Commons left out and reported; the
  countries set for a contest of many; a second import adds nothing (`INSERT IGNORE`).
- Authorization, negative cases first: a juror of the source round, an admin of the target only,
  an inactive membership, a round id of a contest the user isn't in, a nonexistent round: each
  refused with the same 404, no job created; root allowed. The CSRF token on the form, as today.
- The import page: the select lists only the rounds the user may rank.

## Size

About a day: the shared ranking (half a day with its tests), the import source and job step,
the form and its authorization tests. No migration.
