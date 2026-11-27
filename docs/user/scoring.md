# Scores and the worst mark

How a rated round scores its files, the criteria a round can rate on, and the worst mark. The
admins set them on the round form, and the results show the scores and the worst marks.

<a id="a-score-rule"></a>
## The file score

The **File score** option decides how a rated round gives a file its score. The score is shown
in the results and ranks the files, and a next round's minimum score uses it. The three rules
are:

- **Average over the jurors who rated it**, the default: the file's rates added up and divided
  by the number of jurors who rated it.
- **Average over its assigned jurors (unrated counts as 0)**: the rates divided by the number of
  jurors the file was given to, a juror who didn't rate it counting as 0.
- **Total of the rates (a file a juror skipped adds nothing)**: the rates added up, not
  divided.

The results show what the score is divided by, for example "14.50 (29 / 2 of 3 jurors)" or "29
(sum of 3 jurors)".

You can change the rule at any time. It changes the scores and the order of the round's results
on every page at once, and nothing is recomputed in the background. **The page warns you** when
the round has votes and you pick another rule. The warning says that:

- the results are re-scored and re-ordered everywhere, at once;
- a distribution that is already queued or running keeps the rule it started with;
- the later rounds that took their minimum score from this round are named. Their
  files don't change, but a later **Distribute new files** there uses the new rule.

<img src="images/admin-score-rule-warning.png" alt="The file score option and its warning" width="877">

<a id="o-score"></a>
**Organizers:** the admins choose each rated round's rule. The results rank the files by the score
and show what it is divided by.

<a id="a-worst-mark"></a>
## The worst mark

The **Worst mark** option is for rated rounds without criteria. It is off by default. When it is
on, a juror can give a file the worst mark, shown as an X icon, for a file worth fewer than one
star, which is different from leaving it unrated:

- a worst mark counts as 0 in every average and total;
- a worst mark is a vote: the file counts as rated in the juror's progress and in the round's
  count of jurors who rated it;
- the results show the number of worst marks ("n worst marks") beside the file's score, for a
  file that has them: the file list, the by-rate gallery, the large view of all jurors and the
  CSV;
- a next round can use the worst marks in the [rejection filter](round-files.md#a-rejection-filter).

Jurors give a worst mark with a button and clear a vote with another, see [Rated rounds
(stars)](juror-rounds.md#j-rated) in the jurors' pages. The form hides the option for a yes/no round, a
comments-only round and a round with criteria (and refuses it there if it is sent anyway). You can turn it on or off on an existing round.

<a id="a-criteria"></a>
## Criteria

A rated round can ask its jurors for stars on several **criteria** instead of one rate per file,
for example **Aesthetics**, **Technical quality** and **Usefulness for Wikipedia**.

**The criteria catalogue.** The criteria come from one catalogue that the whole tool shares: the
round forms of every contest offer the same criteria. It starts with criteria such as
**Aesthetics**, **Technical quality**, **Usefulness**, **Usefulness for Wikipedia**, **Scientific
value**, **Uniqueness**, **Originality of the photo**, **Caption** and **Extra points**, each
translated into the tool's languages. To see the catalogue, open **Criteria catalogue**, a link
under **Criteria** on the round form. Any contest's admins and root can add a criterion there: type
its name (up to 255 characters) in **New criterion** and press **Add criterion**. The new criterion
is shown by the name you typed in every language until the tool's translators translate it. A name
the catalogue already has, in your language or as typed, is refused. Criteria are never changed or
removed in the catalogue, because rounds of every contest and their rates refer to them; if a name
is wrong, ask the tool's administrators.

**Choosing a round's criteria.** In the round form's **Criteria** section, for a rated round,
**Add criteria** shows the first criterion's list, **Add another criterion** one more, and ×
beside a criterion removes it. A round has 1 to 10 criteria, in the order chosen: jurors see them
in that order. A round with none is rated as usual, one rate per file. The section is hidden for a
yes/no or comments-only round. If the contest's latest round has criteria, a new round starts with
them and with that round's rating type.

**A file's rate.** A juror rates each criterion with the round's stars. A file counts as rated by
that juror once all its criteria are rated, and its rate is the sum of the criteria's stars: with
three criteria on 1–5 stars, from 3 to 15. With one criterion the file's rate is that criterion's
stars, and jurors rate it as in any rated round. The [file score](#a-score-rule) adds up or
averages these sums over the jurors, as for any rated round, and the results also show each
criterion's average (see [Results](results.md#o-results)). A round with criteria has no [worst
mark](#a-worst-mark), and its votes can't be used by the [rejection
filter](round-files.md#a-rejection-filter).

**When criteria can't change.** Once a round is started (running, or with a vote), its criteria
are fixed: the form lists them with a lock and the words "The round's criteria can't be changed
while it is running or once it has votes", and a round started without criteria can't get any. A
round stopped before its first vote keeps the full form. A next round can take a round with
criteria as its previous round only together with rounds rated on the same scale with the same
number of criteria.
