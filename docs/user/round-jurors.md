# A round's jurors

Choosing a new round's jurors, and later pausing a juror, adding one, or moving a juror's unrated
files to the others.

<a id="a-round-jurors"></a>
## Choosing the jurors

A round's **jurors form** is where its jurors are chosen and its files given out: step 3 of the
round's setup ("Round 4 – step 3 of 3: jurors"). It opens from **Distribute to jurors** on the
review page, the round form, the round's statistics or the rounds list, and from the step bar. While the round's images are being selected it shows the progress, and the
form when that is done.

The form shows the number of images that passed the round's filters and, for a contest with
places, the number in each country (region). Then:

- **Jurors**: the people who vote in this round. They show the round's jurors, or, for a round
  without jurors yet, all the contest's jurors; change them as you need. If the contest has no
  jurors yet, the form links the contest's users: add them first.
- **File distribution**: **Each file to all jurors**, or **Each file to N juror(s)**, for contests
  with so many files that each juror takes a part. The files are shared out so that each file goes
  to the number of jurors you chose.
- **Distribute to jurors** gives each juror their share and opens the round to them when it has
  finished. You go to the review page, which shows the progress.

Nothing is distributed before you press the button. The tool refuses, and says why on the form,
when no juror is chosen, when a file would go to more jurors than are chosen, when the round's
jurors changed since you opened the form (open it again), when the last re-apply of its filters
didn't finish, or, without **Distribute anyway**, when the previous rounds' votes changed. Once a
distribution has started (the round has votes), its jurors can't change any more.

<a id="a-pause-juror"></a>
## Pausing a juror

In the **Start/Stop** column of the [statistics page](statistics.md#o-statistics), the
button next to a juror's name pauses their voting in that round (**Pause the juror's voting**),
and the same button resumes it (**Resume the juror's voting**). The round disappears from that
juror's view while they are paused; their votes are kept, and the juror's files stay assigned to
them. Organizers don't have the buttons: their column, **Voting**, shows each juror as
**Active** or **Paused**.

While a background job holds the round (its distribution, a juror being added, or a juror's
files being reassigned), these buttons are refused with a message: try again when the job has finished.

<a id="a-add-juror"></a>
## Adding a juror to a round

Under the jurors' table of the [statistics page](statistics.md#o-statistics) (and of the
round form's jurors panel), **Add juror…** opens a page listing the round's jurors (stopped ones
marked **stopped**). Its list of people to add has only the contest's jurors who aren't in the
round. Choose one and **Show what this does**: the page says how many files they get, or why they
can't be added now. **Add** queues the work; the rounds list's job column shows it, and the number
of files given when it is done. The person must first be a juror of the contest (**Users**).

The round's own jurors aren't in that list. A stopped juror is brought back from their row in the
table (**Start and give a fair share…**, below). An active juror can't be added again: to give them
more files, **Stop** them, then use **Start and give a fair share…**.

- A running or a stopped round takes a juror: its distribution done, and not held by another
  round's distribution or another job. **Add juror…** and **Start and give a fair share…** are
  shown only then. A round still being set up has neither: its jurors are chosen while it is set
  up. A retired contest's rounds take no juror.
- In a round where every file goes to every juror, the new juror gets every file of the round
  they don't have yet, unrated, except files deleted on Commons.
- In a round where each file goes to a set number of jurors, the new juror takes unrated files
  from the jurors ticked on the page, so that they all end with about as many unrated files; the
  page shows how many from each ("Y gets about 1,520: from A 410, B 390 …"). The round's active
  jurors are ticked; stopped ones are listed unticked and marked: a ticked stopped juror gives
  their unrated files and stays stopped. Each juror gives the files assigned to them last first.
  Choose the jurors and **Show what this does** again to see the new figures: until then the
  confirm button is greyed out, with a note to show the figures first.
- In such a round, files **waiting for a juror** (with fewer jurors than the round gives each file,
  for example after a juror's files were reassigned) go to the new juror first, automatically:
  each counts toward the new juror's fair share, and the rest of the share comes from the ticked
  jurors as above. The page shows the split ("Y gets about 81 files: 12 waiting + 69 from
  others"). If more files are waiting than the share, the share is filled from them only and the
  others keep waiting (the page says how many); a file the new juror already has, or has set
  aside, isn't given to them. If no ticked juror has more unrated files than the new juror (their
  share of the others' files is nothing), the new juror still gets every waiting file they may
  take, and nothing from the others ("Y gets the 12 files waiting for a juror that they may
  take").
- Votes already given never move: only unrated files do (in a round with criteria, a file with
  any criterion rated counts as rated).
- A stopped juror's row in the table has **Start and give a fair share…**: the same page with
  that juror as its subject instead of the list. **Start … again** starts them again with a share
  of the others' unrated files (their own unrated files counted). They can then change their
  earlier votes in the round, as after a plain Start.
- A running round pauses for a few seconds while the files are given: its jurors see a page saying
  so ("Reload shortly") and their votes are refused meanwhile; then the round is open again.
- A stopped round stays stopped: its jurors can't vote in it, as before. When it is started again,
  every juror has their share, the new juror too.
- If the job fails, the round is open or stopped as last set (a Start or Stop pressed meanwhile
  is kept), and **Retry** resumes it. **Cancel** leaves the round the same way. A juror the job
  added stays in the round with the files given so far; only if it gave them nothing yet are they
  stopped again (if they weren't active before).
- A juror who is already active in the round can't be added again. To give them the rest of a
  cancelled add, or more files, **Stop** them, then use **Start and give a fair share…**: they get
  only what they are short of.
- Rounds from before the rounds' jurors were stored (old rounds) can't take a juror.

<a id="a-reassign-juror"></a>
## Reassigning a juror's unrated files

When a juror can't go on, their unrated files can go to the round's other jurors. In the
**Start/Stop** column of the [statistics page](statistics.md#o-statistics), the button with
two arrows next to a juror's name (**Reassign files…**) opens a page with the juror's files: how
many they have, how many are rated (a ✗ and, in a round with criteria, a file with any criterion
rated count as rated), how many unrated, and how many set aside (files deleted on Commons or
disqualified). The button is there only while the round is running, for a juror with unrated
files to move, and only if enough other jurors of the round could take them, each with an active
jury membership of the contest: in a round where each file goes to N jurors, at least N others
(stopped ones count: they are started again); in a round where every file goes to every juror, at
least one other active juror. Otherwise add a juror first (**Add juror…**), or resume a stopped
one. Opened directly, the page says why there is nothing to do.

- Only unrated files move. The juror's votes stay as they are, in every view and in the results.
  Afterwards the juror is stopped in the round.
- In a round where each file goes to a set number of jurors, the page lists the round's other
  jurors: the active ones ticked, the stopped ones unticked and marked **stopped**. Ticking a
  stopped juror starts them again, and they can then change their earlier votes in the round, as
  after a plain Start. **Show what this does** shows, for the jurors ticked, how many files each
  gets ("340 unrated files → 4 jurors: A +85, B +85, C +85, D +85"): they end with about as many
  unrated files each. A file goes only to a juror who doesn't have it yet. Until you show the
  figures for the jurors ticked, the confirm button is greyed out, with a note to show them first.
  A juror whose jury membership of the contest isn't active is listed unticked and marked **no
  active membership**: they can't receive files.
- Files that every ticked juror already has can't move: the page says how many. They stay in the
  round **waiting for a juror**, counted under the statistics table while the round is running.
  Adding a juror (see above) gives them to the new juror first. Or tick **Also give out the N
  files waiting for a juror** on the round form (it is off by default) to give each its missing
  jurors from the round's active jurors who don't have it, the least loaded first. Ticked alone,
  it gives out only those files; with **Distribute new files** or a pasted **Add files** list it
  does that too. If the round's last distribution had failed, saving resumes it instead and says
  so: tick the box again once it has finished.
- The juror's unrated files set aside go to ticked jurors too, so that a file restored on Commons
  or requalified comes back to an active juror, not to the stopped one.
- In a round where every file goes to every juror, nothing is given: the other jurors have the
  files already. The juror's unrated files are only taken from them.
- Fewer ticked jurors than the files' number of jurors is refused: add a juror first. So are a
  juror without an active jury membership of the contest, a round that isn't running (start it
  first), and rounds from before the rounds' jurors were stored.
- **Reassign** queues the work; the rounds list's job column shows it. The round pauses for a few
  seconds meanwhile, as when a juror is added. If the job fails, the round is open again and
  **Retry** resumes it. **Cancel** also opens the round; if no file had moved yet, the juror and
  any stopped juror it started are as they were before. If some files had moved already, the
  juror stays stopped with the unrated files not yet moved, and the jurors it started stay started
  (the message after Cancel says so): **run Reassign files… again to move the rest**.
- In a round where every file goes to every juror, the message afterwards says how many unrated
  files were taken from the juror.
- To bring the juror back, use **Start and give a fair share…** (see above).
