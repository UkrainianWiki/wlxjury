# Monuments and authors

A monument's page and its files, and the files' authors, which only the contest's admins,
organizers and root see. Jurors see a monument's page too, with their own files only: see [The
monument's page](juror-large-view.md#j-monument-page).

<a id="o-monuments"></a>
## Monuments

In a monuments contest, the monument's box in a file's large view (in a round and on the contest
files page) ends with one line, for example `46-233-0012 · Gallery 2 · Page →`: the
monument's id, **Gallery N** in a round (see below), and **Page →**, which opens the monument's
page. The page shows the monument as its Wikipedia list has it, links to its list row on
Wikipedia, its Commons category and its Wikidata item where the list has them, and under
**Files**:

- **In the contest: N →**: the contest's files of this monument. The link opens the contest files
  page with only them, under a chip **Monument: …** with their number in a small pill of its own; the **×** on the chip shows all the files
  again. The filter takes the monument's id exactly and keeps to the pages, the region and
  country menus, the author filter and the large view.
- **In round …: N →**: the round's files of this monument, those any juror has (for a juror: only
  their own, and the contest's count isn't shown to them). The link opens the round's gallery
  with only them: all the jurors' votes for you, a juror's own gallery for them.

A round's lists (the gallery, by rate, the file list and the comments report) take the monument
filter too, with a compact chip **Monument: …** in the bar's filters, where the region menu is:
**↗** opens the monument's page and **×** removes the filter. The votes menu and the pager count
only the monument's files, the region menu is hidden while the filter is on (a monument is in one
region), and the filter keeps to the pages, the votes choices, orders, views, the round and juror
menus and the large view, whose next and previous files and votes by keys stay among the
monument's files. In the large view, the monument's line has **Gallery N**, which opens the list
you are in (the same juror, votes choice and order) with only the
monument's N files; it is left out while the list already has only them and when the file is the
list's only one of the monument. For a file whose monument isn't in the tool's monument lists
the box shows the id and **Gallery N**, or just the id when the file is the list's only one of
the monument or the list already has only the monument's files.

The page counts files only in a contest you are a member of, and a round you may see; opened
from elsewhere it shows the monument alone. **Monuments** (`/monuments`) opens a monument's page
by its id.

<a id="o-authors"></a>
## Authors

Jurors never see who took a file. You, the admins and root see the author, as their Commons
account, in these places:

- the large view of a file, in a round and on the contest files page (**Author:**);
- the **Author** column of a round's file list (of all the jurors' votes, and of one juror's in a
  rated round);
- the **Conflicts of interest** tab of the contest files page, which lists the people whose files
  they are.

Each author's name links to their files. Where it leads depends on where you are:

- **In a round** (the round's large view and file list, and a round's authors list): the round's
  list with only that author's files. From the large view and the file list it is the list you
  are in (the same juror, or all the jurors' votes, and the same tab and order); from a round's
  authors list it is the round's gallery of all the jurors' votes. In the large view and the
  authors list a small images icon beside the name opens the contest files page with the author
  instead.
- **In the contest** (the contest's authors list, the contest files page's large view and its
  conflicts of interest): the contest files page with only that author's files.

The contest files page shows a chip **Author: …** above the files, with the number of files in
the current tab in a small pill of its own (a name may end in digits); a round's lists show a compact chip **Author: …** in the bar's filters,
after the monument's, a long name cut short (point at it to see it whole). The **×** on the chip
shows the files of all authors again. On the contest files page the pages, the region and country
menus and the large view keep the author; the tabs show all the contest's files again. In a
round's lists (the gallery, by rate, the file list, the comments report and its CSV) the filter
keeps to the pages, the votes choices, orders, views, the region, round and juror menus and the
large view, whose next and previous files stay among the author's files (a compact chip there
says so). The votes menu and the pager count only the author's files, and the filter combines
with the monument filter and **Only with comments**. The small link **Commons**, with the Commons
logo, beside a name on the contest files page, or **↗** on a round's chip, opens the author's user
page on Wikimedia Commons in a new tab. Every link that leaves the tool opens in a new tab and
shows where it goes after its text: the Commons logo, the Wikipedia "W", the Wikidata logo, or an
arrow for any other site.

The filter takes the author exactly as stored: "Ivan", "ivan" and "Iván" are three different
authors. It is yours, the admins' and root's only: a juror who opens a link with an author gets
their own list without it, and never sees the author.

<a id="o-authors-list"></a>
## The authors list

The **Authors** button next to the contest's name on the contest files page, and the link
**Authors of this round** under a round's **Statistics**, open the authors list. It has each
author of the contest's files, with the number of their files, linked as above. A line above
the table says how many authors and files there are, and how many files the author with the
most has, with their share, for example "Round 2: 87 authors, 482 files; most by one author: 41
(8.5%)". Files without a stored author are counted below that line.

The buttons above the list choose the whole contest or one of its rounds. A round's list counts
only the files in that round. Once the round has votes, it also counts them per author, over
all its jurors:

- a yes/no round: each file counts once, so the three columns add up to the author's files,
  each with its share of them:
  - **Approved**: at least one juror said yes;
  - **Rejected by all**: votes were cast, none of them yes;
  - **Not voted yet**: no vote cast, whether its jurors haven't voted yet or nobody is assigned
    the file.

  **Approval rate** is the share of yes among all the yes and no votes cast on the author's
  files: a file with 1 yes and 2 no counts as approved, and gives an approval rate of 33%. It
  compares authors whatever the number of jurors per file; it is "—" for an author without a
  cast vote. Sorted from the lowest, it shows first the authors the jurors reject the most
  ("—" stays last);
- a rated round: **Rated** (files with at least one rating), **✗ (worst mark)** (files with at
  least one worst mark, in a round that takes the worst mark or has one) and **Average**, the average score of the
  author's rated files in stars, as the round scores a file (see [The file
  score](scoring.md#a-score-rule)). In a round with criteria, the average is per criterion.

Click a column's heading to sort by it, and again to sort the other way. The vote columns sort
by the share of the author's files, so 12 of 14 rejected comes before 30 of 100.
