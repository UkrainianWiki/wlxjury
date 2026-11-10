# What's new in 2026 (up to version 26.11)

This page lists what you will notice in WLX Jury in 2026, up to version 26.11. Earlier
changes of the year were never announced, so they are here too, in [Earlier in
2026](#w-earlier). Each item links to the part of the guide that explains it. Changes that only
developers or operators see are not listed.

<a id="w-earlier"></a>
## Earlier in 2026

Changes made available before version 26.11, by who notices them.

### For everyone, earlier

- **One account for several contests.** A person has one account, a member of any number of
  contests with a role in each, and switches between them with the contest menu in the top bar.
  See [Taking part in several contests](juror-guide.md#taking-part-in-several-contests).
- **Clear access-denied pages,** with a reference you can quote when you ask for help. See
  ["Access denied" and its reference](juror-guide.md#access-denied-and-its-reference).
- **Faster pages:** galleries and region statistics load faster, and the galleries keep your
  place and a stable order.

### For jurors, earlier

- **You see only your own votes.** Other jurors' votes are for the organizers. See [Your own votes
  only](juror-guide.md#your-own-votes-only).
- **Regions:** Wiki Loves Earth regions in the region menu, and the number of files in each
  region. See [Regions](juror-guide.md#regions).
- **Deleted and renamed files:** a file deleted on Commons is hidden, and a renamed one shows
  under its new name. See [Deleted or missing files](juror-guide.md#deleted-or-missing-files).

### For organizers, earlier

- **The contest's files as a gallery,** with regions and a large view. See [The contest's
  files](organizer-guide.md#o-contest-files).
- **The contest's files page has a Deleted on Commons tab** for files deleted on Commons. See
  [The contest's files](organizer-guide.md#o-contest-files).

### For admins, earlier

- **Rounds are prepared in the background.** Creating a round, and **Distribute new files**,
  run as jobs with progress, **Cancel** and **Retry**. A round being created stays hidden from
  jurors until it is ready. See [The life of a round](admin-guide.md#a-stages) and
  [Distributing new files](admin-guide.md#a-distribute-new-files).
- **Import files** and **Update monuments** also run in the background. See [Importing the
  contest's files](admin-guide.md#a-import-files).
- **A double submit no longer creates two rounds.**
- **New filters on the round form:** special nominations, Wiki Loves Earth regions, and category
  operations (**In category A**, **Not in category A**, **In both A and B**, **In A or B (or
  both)**, **In A but not B**). See [Choosing the round's files](admin-guide.md#a-choose-files).
- **Add files to a round from a pasted list.** See [Adding files by
  name](admin-guide.md#a-add-files).
- **Conflicts of interest:** a round can leave out files by the contest's members, with a count
  on the round form. See [Leaving conflicts of interest out of a
  round](admin-guide.md#a-leave-out-conflicts).
- **A local image cache,** where the operators have set it up: admins can use **Download images
  locally**. See [Importing the contest's files](admin-guide.md#a-import-files).

## New in version 26.11

<a id="w-everyone"></a>
### For everyone

- **Light, dark and system colour modes**, chosen with the theme icon in the top bar. See
  [Light, dark or system theme](juror-guide.md#light-dark-or-system-theme).
- **No sign-up page.** Accounts are made only by invitation from a contest's admins. See
  [Accepting your invitation](juror-guide.md#accepting-your-invitation).
- **Sessions you control:** **Keep me signed in** on the sign-in page, **Sign out everywhere**
  on your profile, and sessions that end after a long time without use. See
  [Signing out](juror-guide.md#signing-out).
- **A confirmed email change:** a new email address takes effect when you follow the link sent
  to it. See [Your profile](juror-guide.md#your-profile).
- **More reliable voting:** votes no longer fail when admins change rounds at the same time.

<a id="w-jurors"></a>
### For jurors

- **Rating from a rated round's gallery and list, and voting from the keyboard.** In a rated
  round, each file in the gallery now has stars, and the stars in your list by rate, shown
  before, can now be clicked: click one to rate the file. In a yes/no round's gallery, the thumb of
  your vote now stays pressed, and the thumbs work from the keyboard too (Enter or Space). A
  star vote shows at once; if it fails, the stars go back to your earlier rate. A thumb vote
  shows when it is stored. In both cases a message says why a vote failed, and further clicks on
  a file are ignored until its vote is stored. See [Voting in a yes/no round's
  gallery](juror-guide.md#voting-in-a-yesno-rounds-gallery) and [Rating in a rated round's
  gallery](juror-guide.md#rating-in-a-rated-rounds-gallery).
- **Your rate in the large view:** the number above the stars shows your rate ("–" if none), and
  while you point at a star it shows the rate a click gives. See [Voting in the large
  view](juror-guide.md#voting-in-the-large-view).
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
### For organizers

- **A clearer ranking.** The by-rate gallery, the file list, the CSV and the large view for all
  jurors show each file's score and what it is divided by. See [The score](organizer-guide.md#o-score).
- **Criteria rounds' results:** each criterion's average in the file list, the by-rate gallery,
  the CSV and the large view. See [The file list](organizer-guide.md#o-file-list).
- **Yes/no rounds:** the all-jurors view shows each file's approvals. See [The by-rate
  gallery](organizer-guide.md#o-by-rate).
- **Conflicts of interest page,** with its history, for organizers and admins. See
  [The conflicts page](organizer-guide.md#o-conflicts-page).
- **Each juror's voting state on the statistics page,** **Active** or **Paused**. Pausing a
  juror is for admins. See [Statistics](organizer-guide.md#o-statistics).

<a id="w-admins"></a>
### For admins

- **A choice of the file score** for rated rounds: average over the jurors who rated it,
  average over the assigned jurors, or the total of the rates, with a warning when you change it.
  See [The file score](admin-guide.md#a-score-rule).
- **Yes/no rounds:** a next round can take files **Selected by at least X jurors**. See
  [Choosing the round's files](admin-guide.md#a-choose-files).
- **The worst mark** as a round option, and a **rejection filter** for the next round
  (exclude files rejected by at least X, or take only those). See [The worst
  mark](admin-guide.md#a-worst-mark) and [The rejection
  filter](admin-guide.md#a-rejection-filter).
- **Criteria chosen from a shared catalogue** on the round form. See [Kinds of
  rounds](admin-guide.md#a-round-kinds).
- **Disqualifying and requalifying files** on the conflicts page, while a round is stopped; the
  votes are kept. See [Disqualifying and requalifying](admin-guide.md#a-disqualify).
- **Invitations** are the only way to add people, with a list of invitations not yet accepted,
  to send again or cancel. See [Invitations](admin-guide.md#a-invitations).
- **Shareable set-password links** to pass on yourself, instead of mailing. See [Sharing a link
  instead of mailing it](admin-guide.md#a-share-link).

<a id="w-changed"></a>
### Changes to be aware of

- The **all-jurors by-rate list** no longer counts a juror's missing rate as 0 by default, and
  ranks by the average, not the sum. To count a missing rate as 0, choose **Average over its
  assigned jurors** as the round's file score.
- A round that used the old "optional rate" setting now has the file score **Total of the
  rates**.
- **Several previous rounds:** they must all use the same rating (the same scale and the same
  number of criteria); each is judged on its own, and a file is taken if it passes in at least
  one.
- On an open round, **Leave out conflicts of interest** only affects later distributions. To
  disqualify files now, stop the round and use its conflicts page.
- **Setting another person's password** stays possible, on their edit page (**Set password**),
  for root and, for their own jurors and organizers only, contest admins. It now signs the person
  out everywhere and marks their email unverified, as a link to share that they use does. An
  admin can also copy an invitation or reset link and pass it on in person. See
  [Passwords](admin-guide.md#a-passwords).
- **Old passwords:** an account whose password was stored in the old format keeps signing in
  with it for three months after the release; after that it needs a password reset.
- **Accounts that shared one wiki account** sign in by email, not by the wiki account.
- **Sessions from before the release** carry over for 30 days, then you sign in again.
