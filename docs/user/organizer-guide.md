# WLX Jury: a guide for organizers

This guide is for the organizers of a Wiki Loves Monuments, Wiki Loves Earth or similar photo
contest in WLX Jury: the members of the contest's organizing team who help follow the voting. As
an organizer you read the statistics of every round, the results of all jurors, the file list, the
CSV, the contest's files and the conflicts page, and you may comment on the files of an open
round. You change nothing else: the contest's admins set it up and run the judging, as the [admin
guide](admin-guide.md) explains. Admins see everything in this guide too.

Labels in **bold** are the ones the tool shows in English. Terms: a **contest** is one competition,
a **file** is one photo or video, a **yes/no round** is a round with the rating type **Yes/No**, and
the roles are **Juror**, **Organizer** and **Admin**. Jurors have their own guide, the
[juror guide](juror-guide.md); read its sections on the gallery and the large view to know what
the jurors see.

<a id="o-who-can-do-what"></a>
## Who can do what

The tool has these roles in a contest: **Juror**, **Organizer** and **Admin**. A person can have
a different role in each contest. Roles are explained in the [admin guide](admin-guide.md#a-roles).

| | Juror | Organizer | Admin |
|---|---|---|---|
| Vote | on their own files | no | no |
| Read the statistics, the results of all jurors, the file list and the CSV | no | yes | yes |
| See the contest's files and the conflicts page | no | yes | yes |
| Comment on a file of an open round | their own files | any file of the round | any file of the round |
| Read the [comments report](#o-comments-report) and its CSV | no | yes | yes |
| Set up rounds and files, start and stop rounds, pause jurors, disqualify files, manage people | no | no | yes |

**Root**, the tool's own administrators, can do what an admin can, in every contest.

If a button or page is missing for you, your role in the current contest probably doesn't allow
it. The contest menu at the top of the page shows which contest you are in.

<a id="o-start"></a>
## Where to start

After you sign in, the tool opens the **Statistics** of a round of your contest: the latest
open round, or the latest round if none is open. A contest with no round yet shows **No round
configured yet**.

- The row of round names above the statistics table switches round.
- **Contest files** in the top bar opens the contest's files.
- The contest menu at the top of the page switches contest, if you are in more than one.

There is no **Rounds** or **Users** menu for you: those are the admins'. The admins create the
rounds, give their files to the jurors, start them and stop them; a round being prepared is
hidden from the jurors until all its files are given.

<a id="o-round-kinds"></a>
## Kinds of rounds

The **Rating type** of a round, chosen by the admins, decides what the votes and the results
look like:

- **Yes/No** (a yes/no round): jurors select or reject each file.
- **1-N rating**, a rated round: each juror gives each file from one star up to N stars. Some
  rated rounds use **criteria** (for example Aesthetics, Technical quality, Usefulness for
  Wikipedia): stars on each criterion, and a file's rate is the sum. Some use the **worst mark**,
  shown as an X icon, for a file worth fewer than one star; it counts as 0 in the score.
- **Comments only**: jurors take no votes, only write comments.

The admin guide explains [the kinds of rounds](admin-guide.md#a-round-kinds) as the admins
choose them.

## Following the voting

<a id="o-statistics"></a>
### Statistics

**Statistics** shows a row for each juror of the round: **N**, **Juror name**, **Email**, the
juror's **Voting** state, the number of **Files** the juror has, and the juror's votes: for a
yes/no round **Selected**, **Unrated** and **Rejected**, for a rated round **Rated** and
**Unrated**. The icons next to the counts open the juror's gallery or file list, or only their
selected, unrated or rejected files.

The **Voting** column shows **Active**, or **Paused** when the admins have paused the juror's
voting in this round: the round is then hidden from that juror, and their votes are kept. Admins
see pause and resume buttons in this column instead, see [Pausing a
juror](admin-guide.md#a-pause-juror).

The last row, **Total**, sums up all the jurors, with links to the results of all jurors, see
the next part. In a yes/no round a file with votes of two kinds is counted in each, so the
columns of the totals can add up to more than the files.

Under the table:

- for a rated round, the round's **File score** rule, see [The score](#o-score);
- the link **Conflicts of interest of this round**, see [The conflicts page](#o-conflicts-page);
- **Deleted on Commons**, when some of the round's files were deleted on Commons: the number
  links to these files.

<img src="images/organizer-round-statistics.png" alt="The statistics page, a row for each juror, with the Voting column" width="1280">

## Results

<a id="o-results"></a>
### Reading the results

The bar at the top of a round's results has the menus that show other views of the same
round: the round, the juror (a single juror's votes, or **All**), a vote filter and five views:
the **Gallery**, **By rate**, **File list**, **CSV** and [**Comments**](#o-comments-report). The views of **All** jurors take no
votes. In a window narrower than about 1700 pixels, this bar shows **Contest files** and
**Statistics** as icons (photos and a bar chart) and a single juror's order menu as a sort icon,
and the region menu shows only the chosen place, without the word "Region" (or "Country"); point
at an icon to see its name.

You can see a juror's votes only for a juror of that round. Choosing another person's page
shows the tool's "not found" page.

<a id="o-comments-filter"></a>
**Files with comments:** **Only with comments (N)**, next to the vote filter, shows only the
files with comments in the view you are on: a single juror's files, or all of them for **All**.
It works with the vote filter (for example the selected files with comments), the region, the
order and every view, the CSV included, and N is how many files of the current view have
comments. The large view then moves through those files only. Below about 1700 pixels it is a
speech bubble icon. In the gallery, the by-rate gallery and the large view's **Next files**, a
file with comments shows a speech bubble with their number. Both count the comments the file's
large view shows: from any round of the contest, by anyone.

<a id="o-comments-report"></a>
**Comments report:** the **Comments** view (a speech bubble in the view menu; also **Comments on
the round's files** in the round's statistics) lists the files with comments, 20 to a page. Each
file has its thumbnail and name, which open its large view, its result in the round (the votes of
**All** jurors, or the chosen juror's vote, a rated round's stars), and then its comments, each
with its author, its time and, for a comment from another round, that round's name. Above the
list:

- **This round** (the default) or **All rounds of the contest**: which comments the report shows.
  A file is listed when it has at least one comment of that choice; **All rounds of the contest**
  shows every comment its large view shows.
- The order: the view's own (for **All** jurors the yes/no orders or by rating, and file order;
  for one juror that juror's orders), or **Latest comment first**, by the newest comment of the
  choice above.
- **Comments CSV**: a spreadsheet with one row per comment of the report, in its order: the file,
  its page id and Commons link, the author, the time (UTC), the round and the text.

The juror, vote filter and region menus and the pager work as in the other views, the CSV
included. Only admins and organizers of the contest (and root) can open the report and its CSV;
jurors cannot, even for their own files. Comment authors' names and texts leave the tool in the
CSV: share it only with the contest's team.

In a single juror's **Gallery** each file shows that juror's vote, read-only: in a yes/no round a
green or red frame with a thumb, in a rated round the juror's stars (or ✗).

A single juror's **Gallery** and **By rate** views have an order menu, by that juror's votes:
**In file order**, **By rating**, **Newest votes first** and **Oldest votes first** (when the
juror last changed each vote; votes from before these options count as the oldest, unrated
files come last). With a yes/no round's **Selected** or **Rejected** filter it has **In file
order** and the two vote-time orders; with **Unrated**, the files are in file order. In the
vote-time orders each file shows when the juror voted (in your time zone; **Time not recorded**
for votes from before), and the large view of a single juror's vote shows it in every order. The
views of **All** jurors keep their own order and show no vote times.

<a id="o-score"></a>
### The score

In a rated round each file has a score, which ranks the files. The admins choose how the round
computes it: the average over the jurors who rated the file (the default), the average over the
jurors it was given to, a juror who didn't rate it counting as 0, or the total of the rates. The
results show what the score is divided by, for example "14.50 (29 / 2 of 3 jurors)" or "29 (sum
of 3 jurors)". The rules are in [The file score](admin-guide.md#a-score-rule).

<a id="o-by-rate"></a>
### The by-rate gallery

**By rate** shows the files of the round as a gallery, best first, with each file's rank and
score. In a yes/no round the order depends on the menu next to the views: **Approvals, then
fewest rejections** or **Approvals minus rejections**. The vote filter shows only files with
**Selected**, **Unrated** or **Rejected** votes: for a yes/no round, files with at least one
such vote, or for a rated round **Rated** or **Unrated**. A rated round's score follows its
[file score](#o-score), with the divisor shown. In a round with criteria, each file shows
its stars for each criterion. A file with worst marks shows how many ("n worst marks").

Clicking a file opens its [large view](#o-large-view).

<img src="images/organizer-results-by-rate.png" alt="The by-rate gallery of all jurors" width="1280">

<a id="o-file-list"></a>
### The file list

**File list** shows the files of the round in a table: the rank, the file, its score, and a
column for each juror with their vote. The table also shows the author of each file, for
organizers and admins. In a round with criteria it has the average of each criterion. Use it to
compare the jurors, or to look for a file by name.

When there are many files, the pager at the top works as in the [juror
guide](juror-guide.md#the-pager).

<img src="images/organizer-results-file-list.png" alt="The file list of all jurors" width="1280">

<a id="o-large-view"></a>
### The large view for all jurors

Open a file from the by-rate gallery or the file list to see it large with the votes of **all**
jurors: the file's score and the comments on it. In a round with criteria, it shows the stars
of every criterion. The view takes no votes. The keys that move between files work as in the
[juror guide](juror-guide.md#keyboard-shortcuts-in-the-large-view).

**Comments:** while the round is open, the view has a comment box under the file, as the
jurors' large view has: type your comment and press **Submit comment**. Each comment shows its
author and time (in your time zone; some older comments have none), and the round's name if it
was written in another round of the contest. Only admins, organizers and root see that round
name; jurors see the comment without it. Comments are not
private: whoever opens the file's large view in the contest sees them, with your name: its
admins and organizers, and the jurors who have the file. When the round is stopped, the file's
comments are still shown, but no new ones can be added: the tool refuses a comment on a stopped
round's file, also one sent from a page opened before the round was stopped.

<a id="o-csv"></a>
### The CSV

The **CSV** view downloads the round's results as a file for a spreadsheet. It has a row for
each file, in the order of the list you were on, and the columns **Rank**, **File**, **Overall**
(the score), for a round with criteria a column for each criterion, then a column for each
juror. The last row explains the values: for a yes/no round, 1 selected, -1 rejected, 0 not voted
yet, empty not assigned; for a rated round with the worst mark, -1 is the worst mark.

The file is named **WLXJury_Round_** with the round's name. Cells that start with a character
that a spreadsheet would read as a formula are changed so that they don't run as one.

<a id="o-contest-files"></a>
### The contest's files

**Contest files** in the top bar shows all the files of the contest in a gallery, with the region
menu of a monuments contest, and a large view of each file. It has three tabs:

- **All files**;
- **Deleted on Commons**: the files that were deleted on Commons;
- **Conflicts of interest**: the contest's files that are conflicts of interest, each with
  whose it is, see [What a conflict of interest is](#o-conflicts).

The **Import and cache** button next to the contest's name is only for admins, who bring the
files in with it, see [Importing the contest's files](admin-guide.md#a-import-files).

<img src="images/organizer-contest-files.png" alt="The contest's files as an organizer sees them, with the three tabs and no import button" width="1280">

## Conflicts of interest

<a id="o-conflicts"></a>
### What a conflict of interest is

A file is a conflict of interest when its author is a member of the contest in a role that
gives a conflict of interest (Juror, Organizer, Admin, and the Board, Audit committee and Staff
roles), or a root user. The tool recognises the files by the members' **Wiki account**. A
member without one can't be checked. An admin can set the wiki account, see the [admin
guide](admin-guide.md#a-users).

The admins can leave these files out of a round, and disqualify them from a stopped round,
keeping their votes aside, see [Leaving conflicts of interest out of a
round](admin-guide.md#a-leave-out-conflicts).

<a id="o-conflicts-page"></a>
### The conflicts page

The link **Conflicts of interest of this round**, under the table on the round's
**Statistics** page, opens the page **Conflicts of interest of round …**. (Admins also find a
button on the round form.)

<img src="images/organizer-round-statistics-link.png" alt="The end of the statistics table, with the link Conflicts of interest of this round" width="1280">

The page has:

- **Conflicts of interest in the round**: the files whose author is a conflicted member now,
  each with its author and reason.
- **Disqualified files**: the files taken out of the round.
- **History**: every disqualification and requalification, newest first: when, which file, the
  reason, the status before and after, and who did it.

A round without conflicts, as in the picture, says so in each part. Admins also have the
controls to disqualify and requalify files there, see [Disqualifying and
requalifying](admin-guide.md#a-disqualify); for you the page is read only.

<img src="images/organizer-conflicts.png" alt="The conflicts of interest page of a round with no conflicts, no disqualified files and no history" width="1280">
