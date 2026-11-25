<a id="a-tracks"></a>
# Tracks

A **track** is a line of rounds that belong together: for example the Quality rounds, the
Quantity rounds, the main competition (**Main**), the rounds of a region, or the rounds of a
special nomination. Each round is on one track. The tool finds it by itself; you set a label only
when you want another one.

**How the tool picks a round's track.** It looks at the round's settings and at its previous
rounds (the rounds it was created from), checks these in order, and takes the first that fits:

1. The round has a **track label** (see below): the track of that label.
2. The round has a special nomination, or its region or monument filter names only places of one
   special nomination: that nomination's track.
3. Its previous rounds are all on one nomination's track: it stays on that track, even if it
   takes only some regions.
4. It takes only some of the contest's regions (not all of them): a track of those regions.
5. Its previous rounds are all on one track: it stays on that track.
6. Its previous rounds are on different tracks: **Main**. This is how the main competition
   usually starts, when the Quality and Quantity rounds come together.
7. It has no previous round: a track named after the category it takes its files from, without
   the start that the contest's first rounds' categories share (for example **Quality** for
   "Category:Wiki Loves Monuments in Ukraine 2025 - Quality"); without a category of its own,
   after its media type (for example **Video**); otherwise after the round's own name. First
   rounds with the same category share a track.

**An example**, from Wiki Loves Monuments in Ukraine:

- Q-00 takes the files of "… 2025 - Quality" and Qt-00 those of "… 2025 - Quantity": they start
  the tracks **Quality** and **Quantity** (rule 7).
- Q-01, created from Q-00, stays on **Quality**; Qt-01, created from Qt-00, stays on **Quantity**
  (rule 5).
- Q-02, created from Q-01 and Qt-01, joins two tracks: it is on **Main** (rule 6), and so are the
  rounds created from it.
- A round created from a Main round that takes only one region's files is on that region's track
  (rule 4).

**The track label.** The round form has a field **Track label (optional)** under the round's name,
on a new round and on an existing one, at any time. On an existing round, its help says which
track the tool would pick by itself ("Automatic: …"). Leave it empty to keep the automatic track.

- A label names the round's track. Later rounds created from it, without a label of their own,
  take the same track, as they would an automatic one.
- Rounds with the same label share a track. Type it exactly the same way, capital letters and
  spaces between words included ("Second look" and "Second  look" are two tracks): the field
  suggests the contest's labels as they are spelled. Only spaces at the start and the end are
  dropped. A label has at most 64 characters.
- If the contest's first rounds' categories share no start (common in Wiki Loves Earth), their
  tracks show the categories' full names. Give each such track its own label (for example
  "Quality" and "Quantity"): labelling one first round doesn't shorten the others' names.
- **Main** puts the round on the Main track. It is understood in any capitals and in the tool's
  other languages (Основний, Основной, Principale), and stored as Main.
- Each label change is recorded and shown under **Changes to this round's settings** on the
  round's statistics.

**"Round … joins rounds of several tracks, so it starts the Main track alone."** The Rounds page
shows this when the only round on Main is the one where two tracks meet. In the example above,
Q-00 and Q-01 are the main competition's earlier rounds, but they show as **Quality**. To put them
on Main, open the first round of the main competition (Q-00) and set its track label to **Main**:
the rounds created from it follow. All of them follow, side branches included: if Q-00-1, a second
look at Q-00's files, was also created from Q-00, it joins Main too. To keep such a branch apart,
give its first round a label of its own (for example "Second look"); the rounds created from it
follow that label. The notice is only advice: nothing else changes if you leave it.

**A deleted previous round.** If a round was created from a round that has since been deleted,
the Rounds page says so under it. The deleted round is not counted when the track is picked; a
round with no previous round left starts a track of its own (rule 7).

**The chip and the Track menu.** A coloured chip after a round's name names its track, on the
Rounds page, the round's statistics and the galleries' round menu (jurors don't see it). Main has
a colour of its own; the other colours only tell the tracks apart. A region track's chip names its
regions, shortened if long: point at it for the full text. A first round whose track is named
after the round itself has no chip, since it would repeat its name; the later rounds on its track
show it. The **Track** menu, next to **Status** and **Type** on the Rounds page and the
statistics, shows when the rounds listed are on two tracks or more: **All tracks**, or one track's
rounds, with a count for each. It lists Main first, then the tracks that lead into Main in the
order they join it, then the others. The choice stays in the page's address, as the other menus'
do.
