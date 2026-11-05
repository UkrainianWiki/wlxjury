  package controllers

  import java.io.{StringWriter, Writer}

  import com.github.tototoshi.csv.CSVWriter
  import db.scalikejdbc.{Round, User, VoteJdbc}
  import org.apache.pekko.NotUsed
  import org.apache.pekko.stream.scaladsl.Source
  import org.intracer.wmua.{BinaryOrder, CriteriaRate, CriteriaResults, ImageWithRating}
  import org.intracer.wmua.ImageWithRating.Ranks

  import scala.concurrent.{ExecutionContext, Future}

  object Csv {

    /** Files per query of a streamed CSV ([[rateRows]]). */
    val Chunk = 1000

    def writeStringBuffer(data: Seq[Seq[Any]]): StringBuffer = {
      val writer = new StringWriter()
      write(data, writer)
      writer.getBuffer
    }

    /** One CSV line; every cell through [[cell]], so no export can write a formula. */
    def writeRow(data: Seq[Any]): String = {
      val writer = new StringWriter()
      val csv = CSVWriter.open(writer)
      csv.writeRow(data.map(cell))
      csv.close()
      writer.getBuffer.toString
    }

    /** CSV lines; every cell through [[cell]], so no export can write a formula. */
    def write(data: Seq[Seq[Any]], writer: Writer): Unit = {
      val csv = CSVWriter.open(writer)
      csv.writeAll(data.map(_.map(cell)))
      csv.close()
    }

    /** A cell as written: a number (by type, or a string such as `-1` or `2.5`) as it is, any
      * other string through [[text]].
      */
    def cell(value: Any): Any = value match {
      case s: String if !Number.matches(s) => text(s)
      case other                           => other
    }

    private val Number = """-?\d+(\.\d+)?""".r

    /** The rows of `files`: rank, title, the overall rate, a criteria round's average of each
      * of `criteria` (id and name; their averages by page id and criterion id,
      * [[org.intracer.wmua.CriteriaRate.averages]], U8), then each juror's rate.
      */
    def exportRates(
        files: Seq[ImageWithRating],
        jurors: Seq[User],
        round: Round,
        order: BinaryOrder = BinaryOrder.Default,
        criteria: Seq[(Long, String)] = Nil,
        averages: Map[Long, Map[Long, BigDecimal]] = Map.empty
    ): Seq[Seq[String]] = {
      val ranks = ImageWithRating.rankImages(files, round, order)
      header(jurors, criteria) +: files.zip(ranks).map { case (file, rank) =>
        row(file, rank, jurors, round, criteria, averages)
      } ++: legend(round, files.exists(_.worstMarks > 0)).toSeq
    }

    /** A list's files as [[exportRates]] has them, the header first with a BOM, streamed: the
      * list's page ids and rank keys read at once, then their votes (and in a criteria round
      * their averages of `criteria`, the list juror's or all the jurors') a [[Chunk]] of files
      * at a time, on `ec`, as the rows are sent, never holding the round's votes. The ranks
      * come from that first read, so they follow the rows' order even if votes change
      * mid-export (a row's votes are those when its chunk is read).
      */
    def rateRows(list: RoundList, jurors: Seq[User], criteria: Seq[(Long, String)] = Nil, chunk: Int = Chunk)(implicit
        ec: ExecutionContext
    ): Source[Seq[String], NotUsed] = {
      val query = list.query
      val key = list.rankKey
      val pageKeys = query.pageKeys(key.columns.map(_._1))
      val ranks = Ranks.of(pageKeys.map(_._2))
      val rankOf = pageKeys.map { case (pageId, values) => pageId -> ranks(values) }.toMap
      val juror = Option.when(list.asUserId != 0)(list.asUserId)
      // a rated round's ✗ given while its option was on, for the legend (read with the keys)
      val anyWorst = list.round.isRated && !list.round.worstMark && VoteJdbc.hasRejected(list.round.getId)
      Source.single(addBom(Seq(header(jurors, criteria))).head) ++
        Source.fromIterator(() => pageKeys.map(_._1).grouped(chunk))
          .mapAsync(1)(ids => Future {
            val averages = if (criteria.isEmpty) Map.empty[Long, Map[Long, BigDecimal]]
              else CriteriaRate.averages(list.round.getId, ids, juror)
            query.withVotes(ids) -> averages
          })
          .mapConcat { case (files, averages) =>
            files.map(file => row(file, rankOf.getOrElse(file.pageId, ""), jurors, list.round, criteria, averages))
          } ++
        Source(legend(list.round, anyWorst).toList)
    }

    /** A binary round's last row, after the files: what its "Overall" and juror columns hold,
      * the votes as they are stored (1, -1, 0), the column of a juror the file isn't assigned
      * to empty; a rated round's where it has the worst mark, or any ✗ (`anyWorst`: given while
      * the option was on, kept when it was turned off). In English, as the header is.
      */
    private def legend(round: Round, anyWorst: Boolean): Option[Seq[String]] =
      if (round.isBinary) Some(BinaryLegend)
      else Option.when(round.isRated && (round.worstMark || anyWorst))(WorstMarkLegend)

    val BinaryLegend: Seq[String] =
      Seq("", "Overall: approvals. Jurors: 1 selected, -1 rejected, 0 not voted yet, empty not assigned")

    /** A rated round with the worst mark's last row: its ✗ (rate -1) counted as 0, "✗ n" after a
      * file's score.
      */
    val WorstMarkLegend: Seq[String] =
      Seq("", "Overall: the score, a worst mark counted as 0, then ✗ n: the file's worst marks. " +
        "Jurors: the rate, -1 the worst mark (✗), 0 not rated yet, empty not assigned")

    /** The juror names, criteria names (an admin's fallback text, [[org.intracer.wmua.Criterion]])
      * and file titles here are user text: [[writeRow]] neutralises them.
      */
    private def header(jurors: Seq[User], criteria: Seq[(Long, String)]): Seq[String] =
      Seq("Rank", "File", "Overall") ++ criteria.map(_._2) ++ jurors.map(_.fullname)

    /** A file's row; a criterion's average empty where no juror rated the file in all the
      * criteria (U5).
      */
    private def row(file: ImageWithRating, rank: String, jurors: Seq[User], round: Round,
        criteria: Seq[(Long, String)], averages: Map[Long, Map[Long, BigDecimal]]): Seq[String] = {
      val ofFile = averages.getOrElse(file.pageId, Map.empty)
      // a rated round's file's ✗ (worst marks) after its score: "14.50 (29 / 2) ✗ 1"
      val worst = if (round.isBinary || file.worstMarks == 0) "" else s" ✗ ${file.worstMarks}"
      Seq(rank, file.image.title, file.rateString(round) + worst) ++
        criteria.map { case (id, _) => ofFile.get(id).fold("")(CriteriaResults.format) } ++
        jurors.map(file.jurorRateStr)
    }

    /** Characters that make a spreadsheet read a cell as a formula (OWASP "CSV injection"). */
    val FormulaStarts: Set[Char] = Set('=', '+', '-', '@', '\t', '\r')

    /** Text as text to a spreadsheet: one that starts with a formula character, or with
      * whitespace and then `= + - @`, gets a leading `'`. [[writeRow]] and [[write]] pass every
      * non-numeric cell through it.
      */
    def text(cell: String): String = {
      val formula = cell.headOption.exists(FormulaStarts.contains) ||
        cell.headOption.exists(_.isWhitespace) && cell.dropWhile(_.isWhitespace).headOption.exists("=+-@".contains(_))
      if (formula) "'" + cell else cell
    }

    def addBom(data: Seq[Seq[String]]): Seq[Seq[String]] = {
      val BOM = "\ufeff"
      val header = data.head
      val headerWithBom = (BOM + header.head) +: header.tail
      headerWithBom +: data.tail
    }
  }
