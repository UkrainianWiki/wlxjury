# What's new in 2026 (up to version 26.11)

This page lists what you will notice in WLX Jury in 2026, up to version 26.11. Earlier
changes of the year were never announced, so they are here too, in [Earlier in
2026](#w-earlier). Each item links to the part of the guide that explains it. Changes that only
developers or operators see are not listed.

<a id="w-earlier"></a>
## Earlier in 2026

Changes made available before version 26.11, by who notices them.

**For everyone**

- **One account for several contests.** A person has one account, a member of any number of
  contests with a role in each, and switches between them with the contest menu in the top bar.
  See [Taking part in several contests](juror-guide.md#taking-part-in-several-contests).
- **Clear access-denied pages,** with a reference you can quote when you ask for help. See
  ["Access denied" and its reference](juror-guide.md#access-denied-and-its-reference).
- **Faster pages:** galleries and region statistics load faster, and the galleries keep your
  place and a stable order.

**For jurors**

- **You see only your own votes.** Other jurors' votes are for the organizers. See [Your own votes
  only](juror-guide.md#your-own-votes-only).
- **Regions:** the region menu in the galleries, with Wiki Loves Earth regions and the number of
  files in each. See [Regions](juror-guide.md#regions).
- **Deleted and renamed files:** a file deleted on Commons is hidden, and a renamed one shows
  under its new name. See [Deleted or missing files](juror-guide.md#deleted-or-missing-files).

**For organizers and admins**

- **Rounds are prepared in the background.** Creating a round, and **Distribute new files**,
  run as jobs with progress, **Cancel** and **Retry**. A round being created stays hidden from
  jurors until it is ready. See [The life of a round](organizer-guide.md#o-stages) and
  [Distributing new files](organizer-guide.md#o-distribute-new-files).
- **Import files** and **Update monuments** also run in the background. See [Importing the
  contest's files](organizer-guide.md#o-import-files).
- **A double submit no longer creates two rounds.**
- **Filters on the round form:** media type (images or videos), a minimum resolution and file
  size, special nominations, regions (Ukraine's and Wiki Loves Earth's), and categories, with
  **In category A**, **Not in category A**, **In both**, **In A or B** and **In A but not B**.
  See [Choosing the round's files](organizer-guide.md#o-choose-files).
- **Add files to a round from a pasted list.** See [Adding files by
  name](organizer-guide.md#o-add-files).
- **Conflicts of interest:** a round can leave out files by the contest's members, with a count
  on the round form. See [Conflicts of interest](organizer-guide.md#o-conflicts).
- **The contest's files as a gallery,** with regions and a large view. See [The contest's
  files](organizer-guide.md#o-contest-files).
- **Deleted and renamed files** are handled: see [The contest's
  files](organizer-guide.md#o-contest-files).
- **Better ranking numbers:** a minimum rate can have decimals.

## New in version 26.11

<a id="w-everyone"></a>
## For everyone

- **Light, dark and system colour modes**, chosen with the theme icon in the top bar. See
  [Light, dark or system theme](juror-guide.md#light-dark-or-system-theme).
- **No sign-up page.** Accounts are made only by invitation from a contest's organizers. See
  [Accepting your invitation](juror-guide.md#accepting-your-invitation).
- **Sessions you control:** **Keep me signed in** on the sign-in page, **Sign out everywhere**
  on your profile, and sessions that end after a long time without use. See
  [Signing out](juror-guide.md#signing-out).
- **A confirmed email change:** a new email address takes effect when you follow the link sent
  to it. See [Your profile](juror-guide.md#your-profile).
- **More reliable voting:** votes no longer fail when organizers change rounds at the same time.

<a id="w-jurors"></a>
## For jurors

- **The worst mark** (an X icon) in rated rounds that use it: a button or the Delete key gives a
  file the worst mark, the eraser or Backspace clears your vote. See [Rated rounds
  (stars)](juror-guide.md#rated-rounds-stars).
- **Rounds rated by criteria**: you rate each file on several criteria with stars in the large
  view. See [Rating by criteria](juror-guide.md#rating-by-criteria).
- **A stopped round or juror** is refused cleanly: a vote in a round that isn't open is
  refused with a message and your stored vote stays. See [Rounds you don't
  see](juror-guide.md#rounds-you-dont-see).
- **Disqualified files** (conflicts of interest) leave your list, and come back with your vote if
  requalified. See [Disqualified files](juror-guide.md#disqualified-files).

<a id="w-organizers"></a>
## For organizers

- **A clearer ranking.** The by-rate gallery, the file list, the CSV and the large view for all
  jurors show each file's score and what it is divided by. See [Results](organizer-guide.md#o-results).
- **A choice of the file score** for rated rounds: average over the jurors who rated it,
  average over the assigned jurors, or the total of the rates, with a warning when you change it.
  See [The file score](organizer-guide.md#o-score-rule).
- **Criteria rounds' results:** each criterion's average in the file list, the by-rate gallery,
  the CSV and the large view. See [The file list](organizer-guide.md#o-file-list).
- **Yes/no rounds:** the all-jurors view shows each file's approvals, and a next round can take
  files **Selected by at least X jurors**. See [The by-rate gallery](organizer-guide.md#o-by-rate)
  and [Choosing the round's files](organizer-guide.md#o-choose-files).
- **Conflicts of interest page,** with its history, for organizers and admins. See
  [The conflicts page](organizer-guide.md#o-conflicts-page).

<a id="w-admins"></a>
## For admins

- **The worst mark** as a round option, and a **rejection filter** for the next round
  (exclude files rejected by at least X, or take only those). See [The worst
  mark](organizer-guide.md#o-worst-mark) and [The rejection
  filter](organizer-guide.md#o-rejection-filter).
- **Criteria chosen from a shared catalogue** on the round form. See [Kinds of
  rounds](organizer-guide.md#o-round-kinds).
- **Disqualifying and requalifying files** on the conflicts page, while a round is stopped; the
  votes are kept. See [Disqualifying and requalifying](organizer-guide.md#o-disqualify).
- **Invitations** are the only way to add people, with a list of invitations not yet accepted,
  to send again or cancel. See [Invitations](admin-guide.md#a-invitations).
- **Shareable set-password links** to pass on yourself, instead of mailing. See [Sharing a link
  instead of mailing it](admin-guide.md#a-share-link).

<a id="w-changed"></a>
## Changes to be aware of

- The **all-jurors by-rate list** no longer counts a juror's missing rate as 0 by default, and
  ranks by the average, not the sum. To count a missing rate as 0, choose **Average over its
  assigned jurors** as the round's file score.
- A round that used the old "optional rate" setting now has the file score **Total of the
  rates**.
- On an open round, **Leave out conflicts of interest** only affects later distributions. To
  disqualify files now, stop the round and use its conflicts page.
- **Nobody sets another person's password.** The person chooses it on an invitation or reset
  link, which an admin can also copy and pass on in person. See
  [Passwords](admin-guide.md#a-passwords).
- **Old passwords:** an account whose password was stored in the old format keeps signing in
  with it for three months after the release; after that it needs a password reset.
- **Accounts that shared one wiki account** sign in by email, not by the wiki account.
- **Sessions from before the release** carry over for 30 days, then you sign in again.
