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
7. It has no previous round: a track named after the part of its category that tells it apart.
   The contest's own words are dropped: first the words all the first rounds' categories start or
   end with (**Quality** for "Category:Wiki Loves Monuments in Ukraine 2025 - Quality"), and when
   they share none, the contest's name, year and country, "Images from" and "in" at either end
   (**Quality** for "Category:Wiki Loves Earth Ukraine 2026 - Quality", **Nature Loss Special
   Category** for "Category:Images from Wiki Loves Earth 2026 Nature Loss Special Category"). A
   category made only of such words counts as no category, and so does a list of files or a
   Commons page in the category field. Without a category, the round is named after its media type
   if the first rounds use more than one, a round with none set counting as images (for example
   **Video** next to the others; two such video rounds share the **Video** track); otherwise after
   the round's own name, without the contest's own words at its ends ("Wiki Loves Earth 2026 in
   Kyrgyzstan 1 round": **1 round**). First rounds with the same category share a track, also
   when it is written in other capitals.

In a contest without a Main track (no round joins tracks and none is labelled Main), the chips and
the **Track** menu call one track **Main**: the track of the contest's first round that isn't alone
on a track named after itself, when that track is named after the round (for example "Round 1",
with the later rounds created from it), instead of repeating "Round 1" on every later round. It
doesn't happen when that round's track comes from a category, a label, a special nomination, a
media type or regions, or when another track is named Main in one of the tool's languages. This
changes only the name shown: the track's links and colour stay the same, and the round form's
"Automatic: …" still gives its own name ("Round 1"), so leave the label empty rather than typing
Main there.

**An example**, from Wiki Loves Monuments in Ukraine:

- Q-00 takes the files of "… 2025 - Quality" and Qt-00 those of "… 2025 - Quantity": they start
  the tracks **Quality** and **Quantity** (rule 7).
- Q-01, created from Q-00, stays on **Quality**; Qt-01, created from Qt-00, stays on **Quantity**
  (rule 5).
- Q-02, created from Q-01 and Qt-01, joins two tracks: it is on **Main** (rule 6), and so are the
  rounds created from it.
- A round created from a Main round that takes only one region's files is on that region's track
  (rule 4).

<a id="a-track-label"></a>
**The track label.** The round form has a field **Track label (optional)** under the round's name,
on a new round and on an existing one, at any time. Empty, it shows the track the tool picks by
itself ("Automatic: Quality"; on a new round just "Automatic", since its track comes from its
previous rounds once it is saved). Leave it empty to keep the automatic track.

- A label names the round's track. Later rounds created from it, without a label of their own,
  take the same track, as they would an automatic one.
- Rounds with the same label share a track. Type it exactly the same way, capital letters and
  spaces between words included ("Second look" and "Second  look" are two tracks): the field
  suggests the contest's labels as they are spelled. Only spaces at the start and the end are
  dropped. A label has at most 64 characters.
- If an automatic name is still long or unclear, give the track a label of its own (for example
  "Quality" and "Quantity"): labelling one first round doesn't change the others' names.
- Label a track on its **first** round, or leave the field empty. A label typed on a later round,
  even the same name as the automatic track (for example "Quality" on Q-01), starts a second track
  with that name from there: two tracks named alike. On a later round the field shows "Automatic:
  Quality, from its previous rounds" when it already takes that track.
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
Rounds page (jurors don't see it). Main has
a colour of its own; the other colours only tell the tracks apart. A region track's chip names its
regions. A long name shows its start and its end: point at the chip for the full text. A round
on a track named after it that no other round continues has no chip: it would only repeat the
round's name. Chips show only when the contest's rounds are on two tracks or more besides those,
and then on every other round; otherwise there are no chips, as in a contest whose rounds were
each created on their own. The **Track** menu, next to **Status** and
**Type** on the Rounds page and the statistics, and on the authors list, shows by the same rule: **All tracks**, or one track's
rounds, with a count for each. It lists Main first, then the tracks that lead into Main in the
order they join it, then the others. The choice stays in the page's address, as the other menus'
do.

**The round tabs.** Round statistics and the authors list show the contest's rounds as tabs.
When grouped by track, a coloured track badge appears before each group; when ungrouped, each tab
has its track badge. The **Track** menu narrows the tabs to a track. On statistics, **Order**
changes their order and
**Group by track** turns grouping off and on. These choices stay in the address and follow you
between statistics and the authors list. On the authors list they affect the round tabs, not the
authors table's column sort.
The galleries' round menu (galleries, by rating, the large view) groups the rounds the same way,
each track's name as a heading in the menu before its rounds, without chips; jurors' menus are not
grouped.
