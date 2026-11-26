<a id="a-review"></a>
# Reviewing a round's images

A new round's images are selected first and wait on the round's **review page**, so a wrong filter
is caught before any juror gets a file. The page is step 2 of the round's setup: once the files
are found, its heading says how many match ("12 files match (the contest has 340)") and **Distribute
to jurors** (which opens the jurors form, step 3) is its main button; while they are being found,
or if none matches, that button is disabled and says why. The rounds list shows a round being set
up as **Setting up – step 2 of 3: check files** (with **Check the files**) or **Setting up – step 3
of 3: choose jurors** (with **Distribute to jurors**) in place of Start; the round form, the
round's statistics and its conflicts page show the same step with a link to it.

The review page shows the round's files as the contest's files page does (tiles, pages, the place
menu, the large view), with tabs for the files **In the round**, **Deleted on Commons** and
**Disqualified**, each with its number of files after its name. Above them: how the files were added (by the round's filters, from a pasted
list, ...), the round's conflicts of interest, if it has any, and its jurors.

- The **place menu** in the bar lists the contest's places with the files of the tab shown in each:
  the countries of an international contest (by the contest's country categories), the regions of
  a national one. Open it to check that each country (region) has the number of files it should;
  choosing a place shows only its files.
- In a file's large view you and the contest's organizers can write **comments**. They are the
  round's ordinary comments: jurors see them once the round is distributed, not before (also
  the jurors of other rounds with the same file).
- **Distribute to jurors** opens the round's jurors form ([Choosing the jurors](round-jurors.md#a-round-jurors)).
  Until the round is distributed it is hidden from the jurors: they don't see it in their menus,
  its gallery or its large view.
- To change the files, open **Edit the round**. While the round waits for review and has no votes,
  its filters (previous rounds, categories, regions, sizes, ...) can be changed and the main button
  is **Re-apply filters**: the images are selected again. Files the filters chose before (also those
  **Distribute new files** added) that no longer pass are taken out, new matches are added, a top-N is chosen again from scratch (files you
  disqualified don't take a place in it). Files added from a list and disqualified files stay as
  they are. The review page then shows how many files were added and removed, and the first
  removed files by name. Check the files again, and re-apply again if needed. If a re-apply fails or
  is cancelled, the review page says so and **Distribute to jurors** waits until you re-apply the
  filters again or retry its job. The other settings of the form (its name, ...) are saved only with a
  re-apply that starts.
- If votes in the previous round(s) change after the images were selected, the review page and the
  jurors form warn you. Re-apply the filters to select by the current votes, or tick **Distribute
  anyway** on the jurors form to give out the images as they are.
- A file deleted on Commons while the round waits is never given to a juror.
- If you cancel a round's creation on its job, the round is cancelled. **Distribute new files** on
  its form reinstates it: its images are selected again and it waits for review again. The tool
  records who did it.

The contest's organizers see the review page too, without the Distribute button; they can't
open the jurors form or re-apply the filters.
