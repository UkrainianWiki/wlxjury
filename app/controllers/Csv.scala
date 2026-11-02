  package controllers

  import java.io.{StringWriter, Writer}

  import com.github.tototoshi.csv.CSVWriter
  import db.scalikejdbc.{Round, User}
  import org.apache.pekko.NotUsed
  import org.apache.pekko.stream.scaladsl.Source
  import org.intracer.wmua.{BinaryOrder, ImageWithRating}
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

    def writeRow(data: Seq[Any]): String = {
      val writer = new StringWriter()
      val csv = CSVWriter.open(writer)
      csv.writeRow(data)
      csv.close()
      writer.getBuffer.toString
    }

    def write(data: Seq[Seq[Any]], writer: Writer): Unit = {
      val csv = CSVWriter.open(writer)
      csv.writeAll(data)
      csv.close()
    }

    def exportRates(
        files: Seq[ImageWithRating],
        jurors: Seq[User],
        round: Round,
        order: BinaryOrder = BinaryOrder.Default
    ): Seq[Seq[String]] = {
      val ranks = ImageWithRating.rankImages(files, round, order)
      header(jurors) +: files.zip(ranks).map { case (file, rank) => row(file, rank, jurors, round) } ++:
        legend(round).toSeq
    }

    /** A list's files as [[exportRates]] has them, the header first with a BOM, streamed: the
      * list's page ids and rank keys read at once, then their votes a [[Chunk]] of files at a
      * time, on `ec`, as the rows are sent, never holding the round's votes. The ranks come
      * from that first read, so they follow the rows' order even if votes change mid-export
      * (a row's votes are those when its chunk is read).
      */
    def rateRows(list: RoundList, jurors: Seq[User], chunk: Int = Chunk)(implicit
        ec: ExecutionContext
    ): Source[Seq[String], NotUsed] = {
      val query = list.query
      val key = list.rankKey
      val pageKeys = query.pageKeys(key.columns.map(_._1))
      val ranks = Ranks.of(pageKeys.map(_._2))
      val rankOf = pageKeys.map { case (pageId, values) => pageId -> ranks(values) }.toMap
      Source.single(addBom(Seq(header(jurors))).head) ++
        Source.fromIterator(() => pageKeys.map(_._1).grouped(chunk))
          .mapAsync(1)(ids => Future(query.withVotes(ids)))
          .mapConcat(_.map(file => row(file, rankOf.getOrElse(file.pageId, ""), jurors, list.round))) ++
        Source(legend(list.round).toList)
    }

    /** A binary round's last row, after the files: what its "Overall" and juror columns hold,
      * the votes as they are stored (1, -1, 0), the column of a juror the file isn't assigned
      * to empty. In English, as the header is.
      */
    private def legend(round: Round): Option[Seq[String]] = Option.when(round.isBinary)(BinaryLegend)

    val BinaryLegend: Seq[String] =
      Seq("", "Overall: approvals. Jurors: 1 selected, -1 rejected, 0 not voted yet, empty not assigned")

    private def header(jurors: Seq[User]): Seq[String] =
      Seq("Rank", "File", "Overall") ++ jurors.map(juror => text(juror.fullname))

    private def row(file: ImageWithRating, rank: String, jurors: Seq[User], round: Round): Seq[String] =
      Seq(rank, text(file.image.title), file.rateString(round)) ++ jurors.map(file.jurorRateStr)

    /** Characters that make a spreadsheet read a cell as a formula (OWASP "CSV injection"). */
    val FormulaStarts: Set[Char] = Set('=', '+', '-', '@', '\t', '\r')

    /** A cell of user text (a juror's name, a file title) as text to a spreadsheet: one that
      * starts with a formula character gets a leading `'`. Numbers the app writes (ranks, rates,
      * a binary round's `-1`) are not passed through it.
      */
    def text(cell: String): String =
      if (cell.nonEmpty && FormulaStarts.contains(cell.head)) "'" + cell else cell

    def addBom(data: Seq[Seq[String]]): Seq[Seq[String]] = {
      val BOM = "\ufeff"
      val header = data.head
      val headerWithBom = (BOM + header.head) +: header.tail
      headerWithBom +: data.tail
    }
  }
