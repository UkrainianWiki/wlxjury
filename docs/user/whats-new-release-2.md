# What's new in release 2

This page lists what you will notice in the WLX Jury Tool after release 2, compared with the
release in use before it (which already had background round preparation, one account for several
contests, category filters, regions and conflicts-of-interest filters). Each item links to the part
of the guide that explains it. Changes that only developers or operators see are not listed.

<a id="w-everyone"></a>
## For everyone

- **Light, dark and system colour modes**, on a refreshed layout. Choose yours with the theme
  icon in the top bar; the tool remembers it for your account. See [Light, dark or system
  theme](juror-guide.md#light-dark-or-system-theme).
- **No sign-up page.** Accounts are made only by invitation from a contest's organizers. See
  [Accepting your invitation](juror-guide.md#accepting-your-invitation).
- **Sign in with Google or Wikimedia**, to the account you already have, if your installation
  offers it. You link the account on your profile. See [Signing in with Wikimedia or
  Google](juror-guide.md#signing-in-with-wikimedia-or-google).
- **Sessions you control:** **Keep me signed in** on the sign-in page, **Sign out everywhere**
  on your profile, and sessions that end after a long time without use. See
  [Signing out](juror-guide.md#signing-out).
- **A confirmed email change:** a new email address takes effect when you follow the link sent
  to it. See [Your profile](juror-guide.md#your-profile).
- **Faster pages:** the organizers' result pages load faster, and votes no longer fail when
  organizers change rounds at the same time.
- **Personal API tokens** on your profile, for command-line tools. See [API
  tokens](admin-guide.md#a-api-tokens).

<a id="w-jurors"></a>
## For jurors

- **The worst mark (✗)** in rated rounds that use it: a button on the file and in the large view,
  or the Delete key, gives a file ✗; the eraser or Backspace clears your vote. See [Rated rounds
  (stars)](juror-guide.md#rated-rounds-stars), [Rating in a rated round's
  gallery](juror-guide.md#rating-in-a-rated-rounds-gallery) and [Keyboard
  shortcuts](juror-guide.md#keyboard-shortcuts-in-the-large-view).
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
- **Yes/no rounds:** the all-jurors view shows each file's approvals, you can choose the
  order **Approvals, then fewest rejections**, and a next round can take files **Selected by at
  least X jurors**. See [The by-rate gallery](organizer-guide.md#o-by-rate) and [Choosing the
  round's files](organizer-guide.md#o-choose-files).
- **Conflicts of interest page,** with its history, for organizers and admins. See
  [The conflicts page](organizer-guide.md#o-conflicts-page).

<a id="w-admins"></a>
## For admins

- **The worst mark (✗)** as a round option, and a **rejection filter** for the next round
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
  instead of mailing it](admin-guide.md#a-share-link). *(New in this release.)*
- **Check round images** on a round, for when the tool's administrators ask. See [Changing a
  round](organizer-guide.md#o-edit-round).

<a id="w-changed"></a>
## Changes to be aware of

- The **all-jurors by-rate list** no longer counts a juror's missing rate as 0 by default, and
  ranks by the average, not the sum. To count a missing rate as 0, choose **Average over its
  assigned jurors** as the round's file score.
- A round that used the old "optional rate" setting now has the file score **Total of the
  rates**.
- On an open round, **Leave out conflicts of interest** only affects later distributions. To
  disqualify files now, stop the round and use its conflicts page.
