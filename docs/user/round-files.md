# Choosing a round's files

The round form's **Media filtering** and its other ways to fill a round: earlier rounds' votes,
categories, regions and sizes, a pasted list, and files added to the contest later.

<a id="a-choose-files"></a>
## Choosing the round's files

The **Media filtering** section of the form decides which of the contest's files go into the
round. Leave a filter at its default to not use it. Filters add up: a file must pass all of them.
A round for all the files takes no filters at all.

**From earlier rounds**

- **Previous rounds**: take the files of one or more earlier rounds, which must all be rated the
  same way (all yes/no, all comments only, or all rated on the same scale with the same number
  of criteria). With none chosen, the files come from the whole contest, and the filters below
  that name earlier rounds don't apply. With several, each earlier round a file was in is
  checked on its own, with its own jurors, votes and file score: a file is taken if it passes
  the filters in at least one of them.
- **Selected by at least X jurors**: for earlier yes/no rounds. A file is taken when at least X
  jurors selected it (X from 1 to 6). **No filter** takes all the files of the earlier round.
- **Number of top files from the previous rounds** (the cut): take only the best files of the
  earlier rated or yes/no rounds. The cut list loads when you choose a previous round. See
  [The top files](#a-top-files).
- The **rejection filter**: take or drop files by how many jurors rejected them in the earlier
  rounds. See [The rejection filter](#a-rejection-filter).
- <a id="a-leave-out-rounds-setting"></a>**Leave out files that are in round(s)**: choose one or more other rounds of the contest. A file
  that is in any of them isn't taken, whatever the votes it got there. It works with or without
  previous rounds, and with every other filter. See the [example](#a-leave-out-rounds) below.

**By the file itself**

- **Media Type**: **All**, **Video** or **Image**.
- <a id="a-category-first"></a>**Only files in category**: a Commons category. Only the contest's files that are directly in
  it are taken, not the files of its subcategories.
- <a id="a-category-second"></a>**Leave out files in category**: a Commons category whose files are not taken. Fill either
  box, both or neither: with both, the round takes the files of the first category that are not
  in the second. Typing in either box suggests Commons categories.
- <a id="a-category-options"></a>**More category options** (closed at first): what the second box does. **Leave its files out**
  is the default. **Files must be in both categories** takes only the files that are in the two
  categories, and the second box reads **…and also in category**. **Files may be in either
  category** takes the files of both, and the second box reads **…or in category**. These two
  need both boxes filled. The **Media filtering** section opens by itself when the round has a
  category.
- **Regions** and **Monument ids (comma separated)**: for contests where the tool knows the
  regions (at present Ukraine's, Wiki Loves Monuments and Wiki Loves Earth alike). Choose one or more
  regions, or list monument ids. A file's region is the first part of its monument id (`07` of
  `07-101-0001`). The list has only the real regions, so a file of a special nomination (its own
  monument id prefix) or a file without a monument id is left out by any choice of regions. To
  leave out just these files, tick **Select all** at the top of the regions list.
- **Minimum resolution (Mpx)** and **Minimum file size (MB)**: leave out small files.
- **Special nomination**: only the files of one special nomination, for contests that have them.

Most of these filters can only be set when the round is created. On an existing round the form
shows them, but they can't be changed.

<a id="a-leave-out-rounds"></a>
**Example: the files of the previous round that didn't pass into round X.** Round Q-00 still has
files nobody voted for, and round Q-01 took its selected files. A round Q-00-1 for the rest of
Q-00 (its rejected and unvoted files), of 4 Mpx and up, without the special nominations, with one
of four jurors per file, yes/no:

- **Previous rounds**: Q-00. **Selected by at least X jurors**: **No filter** (all of Q-00's files).
- **Leave out files that are in round(s)**: Q-01.
- **Minimum resolution (Mpx)**: 4.
- **Regions**: **Select all** (the special nominations' files have no region of the list).
- **Jurors**: the four jurors; **File distribution**: each file to 1 juror; **Rating type**: Yes/No.

The round keeps the setting: "Distribute new files" later leaves out the files that are in Q-01
then. The files of the rounds to leave out are read while the round's distribution runs, so a file
that reaches Q-01 after that stays in Q-00-1 too. The form therefore refuses a round to leave out
that is still being distributed, or whose creation's distribution failed ("The round to leave out,
Q-01, is still being distributed, or its distribution failed…"): let it finish, or retry it, then
save again. In the same way "Distribute new files" on Q-01 (or resuming its failed distribution) is
refused while Q-00-1 is being filled ("A round that leaves out this round's files, Q-00-1, is still
being filled…"), and a Retry of Q-01's distribution on its job page fails with that message:
distribute again when Q-00-1's distribution has finished. The form also refuses the round itself, rounds of another contest ("The
rounds to leave out must be other rounds of this contest") and a round that is also a previous
round of the new round, which would leave it with no files ("A round to leave out can't also be a
previous round of this round…").

<a id="a-top-files"></a>
## The top files (the cut)

**Number of top files from the previous rounds** takes only the best files of the earlier rounds:
for example the 50 best-rated files of a regional round go on to the national round. It shows once
you choose previous rounds that are rated or yes/no. The files are ranked best first by the earlier
rounds' votes, after the section's other filters, and the round takes those above a **cut**.

**Choosing the cut.** The form loads the cut list as soon as you select a previous round, or
when you open a form that already has one selected. Each option shows the number of files and
the last score included, such as **top 88 · score ≥ 8.0**. For a yes/no round it shows the last
vote level included, such as **top 88 · 3 yes, 0 no**.

- Files with the same score are never split, so only cuts between two different scores are
  listed: you can't pick a number that falls inside a tie. Under the list, the form says how many
  files can be taken in all.
- **Candidate cuts** come first, each with its reason: a clear drop in the scores ("scores drop
  8.2 → 7.1", "the largest drop"), the files close to the best score, all the files, or the edge
  of a tie ("includes the tie at 7.5 (places 41–44)", "before the tie at …"). They are looked for
  among the first 100 places; **Other cuts** lists the rest.
- **No top N** takes all the matching files.
- The list refreshes in place when you change the previous rounds or a filter that affects the
  ranking. If the earlier rounds’ votes changed since the cut was chosen, the form tells you.
  If you save during a refresh, the form waits for it to finish.

Nothing is saved until you save the form. The round's [change log](statistics.md#o-round-changes)
records the cut and how it was chosen (from the candidate cuts, with the reason, or from the list).

**The round keeps** (earlier rated rounds): **the files scoring at least the cut's score** (the
default) or **the top N files**. A score can't fall inside a tie, so the files scoring at least it
are taken even if votes change before the round's files are chosen (their number may then change).
The top N takes exactly N files; if votes change so that N falls inside a tie, the round's setup
stops and asks for a new N. The score follows the earlier round's [file score rule](scoring.md#a-score-rule).
For a round with [criteria](scoring.md#a-criteria), each juror's mark for a file is the sum of
their criteria marks. The round's rule then averages or totals those sums across jurors. The hint
under the choice says which rule applies.
Earlier yes/no rounds always keep the top N.

<a id="a-top-files-binary"></a>
**Top files of a yes/no round by** (earlier yes/no rounds) decides how their files are ranked for
the cut:

- **Approvals, then fewest rejections**, the default: the most "yes" votes first, and among those
  the fewest "no";
- **Approvals minus rejections**: "yes" votes less "no" votes;
- **Approvals only**: the "yes" votes alone.

A round created before this choice existed shows **Legacy rule** and keeps it. The results' menu of
orders for a yes/no round uses the same orders, see [Results](results.md#o-results).

**Show this cut in the ratings**: opens the earlier round's ratings in a new tab, on the page with
the cut, with a line between the last file taken and the first one left out. It is offered only
when those ratings list the same files in the same order: one earlier round, none of the other
filters of this section (leaving out conflicts of interest counts only when one of their files is
in that round), and all the jurors' votes visible to you there (not while you are a juror of that
round and it is open).

**Rounds saved earlier.** A round saved earlier with both a number of top files and a minimum score
keeps them. Re-applying its filters asks you to choose one cut; a number that now falls inside a
tie is explained ("N = 45 is inside a tie (places 41–48); choose 40 or 48"), and you choose
another. On an existing round whose filters can't be changed, the cut is shown locked.

<a id="a-rejection-filter"></a>
## The rejection filter

The rejection filter uses how many jurors rejected each file in an earlier round, each earlier
round on its own. It works with earlier yes/no rounds, and with earlier rated
rounds without criteria, where a worst mark counts as a rejection. It shows once you choose
such a previous round.

It has two fields. **Rejected files** is the mode:

- **No filter (keep rejected files)**: rejections don't matter.
- **Exclude files rejected by at least X jurors**: a file is left out when X or more jurors
  rejected it. This drops the files that jurors agreed were bad, for example after a screening
  round where jurors rejected only the few worst files. With several earlier rounds, a file is
  left out only if X or more jurors rejected it in each of the earlier rounds it was in.
- **Only files rejected by at least X jurors**: a file is taken only when X or more jurors
  rejected it. This is for a round that looks again at the files that were doubted.

The second field, **X: rejected by at least X jurors**, is the number, from 1 to 6. The filter
combines with the other filters of the round.

The form refuses these choices: a mode with no X ("Choose X, the number of jurors…"), an X with
**No filter** ("Choose whether to exclude the rejected files or take only them, or clear X"), and
the filter with no previous round that has rejections ("The rejected files filter needs a previous
round with rejections: a yes/no round, or a rated round without criteria").

<a id="a-add-files"></a>
## Adding files by name

In **Add files**, type or paste file names or Commons addresses, one per line, in **Files to
add**. The tool adds these files to the round whatever its filters say. Files already in the
round are skipped. If a line doesn't name a file, nothing is added and the page lists the lines.
You can add files this way both when you create a round and later.

<a id="a-distribute-new-files"></a>
## Distributing new files

When files are added to the contest after a round was created, or a file list is added to it,
the round can take them. Open the round form: the box **Distribute N new files** is there, and
**Count** shows how many files the round would take. Tick the box and press **Save** to start the
distribution of these files to the round's jurors, by the same filters as before.

The round keeps its own settings. For example, a next round whose files were taken from this
round by its score keeps the rule it started with.
