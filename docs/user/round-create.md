# Creating and changing a round

The round form, the background job that gives a new round's files to its jurors, and what can
change on an existing round. Which files a round takes is in [Choosing a round's
files](round-files.md), its jurors in [A round's jurors](round-jurors.md).

<a id="a-create-round"></a>
## Creating a round

Press **Add round**. The page **Create round** has these parts, from the top:

- **Round name (optional)**: a name for the round next to its number.
- **Track label (optional)**: usually left empty; see [Tracks](tracks.md#a-tracks).
- **Media filtering**: which files the round takes. [Choosing files](round-files.md#a-choose-files) describes it.
- **Leave out conflicts of interest**: see [Leaving them out of a round](conflicts.md#a-leave-out-conflicts).
- **Add files**: a list of files to add, see [Adding files by name](round-files.md#a-add-files).
- **Rating type**: see [Kinds of rounds](rounds.md#a-round-kinds).
- **File score**: see [The file score](scoring.md#a-score-rule).
- **Worst mark**: see [The worst mark](scoring.md#a-worst-mark).
- **Criteria**: see [Kinds of rounds](rounds.md#a-round-kinds).

A new round is set up in numbered steps, shown in a step bar at the top of each page:
**1 Which files** (this form), **2 Check the matching files** (the round's review page),
**3 Jurors** (its jurors form), then **Open to jurors**. The form has no jurors: nothing goes to
the jurors until you choose them on the round's **jurors form** and distribute it
([Choosing the jurors](round-jurors.md#a-round-jurors)). The form ends with one button, **Save and find matching
files**: the tool picks the contest's files that match the settings, for you to check, which can
take a few minutes. You land on the round's review page (step 2), which says "Round N is saved.
Step 2 of 3: its matching files are being found." The round waits there, hidden from the jurors:
see [Reviewing a round's images](round-review.md#a-review).

**Cancel** leaves the form without saving anything.

If you press Save twice, or send the form again from the browser's Back button, the tool makes
one round, not two, and tells you.

The jurors are chosen on the jurors form when the round is distributed, and can't be changed on
the form of a distributed round. To work with other jurors, create another round. Later you can pause and resume
a juror, see [Pausing a juror](round-jurors.md#a-pause-juror).

<img src="images/admin-new-round-form.png" alt="The round form, with the Media filtering and Add files sections opened, and the criteria of the contest's latest round" width="853">

Here the contest's latest round has criteria, so the form starts with them and its 1–5 rating;
with criteria the form has no worst mark.

<a id="a-distribution"></a>
## The distribution of files

After you save a new round, the tool gives its files to its jurors as a **background job**. The
rounds list, and the top of the round form, show the job under **Distribution**: its state,
for example queued or running, and its progress as a number of files done out of the total.

While the job runs the round is hidden from jurors, and you can't start it: the tool says
that its images are still being distributed. The round starts by itself when the distribution
has finished. You can leave the page and come back.

- **Cancel** stops the job. It shows while the job runs and after it fails.
- **Retry** runs a failed job again, from where it stopped. It shows only for a failed job.

If a job fails, the state shows why. A message with a **reference** is for the contest's tool
administrators: send them the reference. If the tool stopped for a while while a job was running,
it takes the job up again by itself.

A round has one distribution job at a time. Starting a second one for the same round, for
example from another browser tab, tells you that its images are already being distributed.

<a id="a-edit-round"></a>
## Changing a round

Open the round from the rounds list: its number is a link. On an existing round you can change:

- the round's name;
- the track label, at any time, see [Tracks](tracks.md#a-tracks);
- the [file score](scoring.md#a-score-rule) and, in a rated round, the [worst mark](scoring.md#a-worst-mark), at any time;
- in a rated round, the criteria, only before the round is started (not running and no vote
  yet; a yes/no or comments-only round's form has neither);
- **Leave out conflicts of interest**, see [Leaving them out of a round](conflicts.md#a-leave-out-conflicts);
- **Add files** and **Distribute N new files**.

The rest of the form is shown but can't be changed: the files' filters, the rating type and the
file distribution. To start over, create another round. The panel **Jurors** shows each
juror's counts, as the [statistics page](statistics.md#o-statistics) does.

**Cancel** leaves without saving anything.

At the bottom of the form of an existing round there are also the button **Conflicts of
interest of this round**, and, where the tool's operators have set up a local image cache,
**Download images locally**, which fills it for this round's files.
