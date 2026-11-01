  package controllers

  import java.io.{StringWriter, Writer}

  import com.github.tototoshi.csv.CSVWriter
  import db.scalikejdbc.{Round, User}
  import org.apache.pekko.NotUsed
  import org.apache.pekko.stream.scaladsl.Source
  import org.intracer.wmua.ImageWithRating
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

    def exportRates(files: Seq[ImageWithRating], jurors: Seq[User], round: Round): Seq[Seq[String]] = {
      val ranks = ImageWithRating.rankImages(files, round)
      header(jurors) +: files.zip(ranks).map { case (file, rank) => row(file, rank, jurors, round) }
    }

    /** A list's files as [[exportRates]] has them, the header first with a BOM, streamed: the
      * list's page ids read at once, then their votes a [[Chunk]] of files at a time, on `ec`,
      * as the rows are sent; ranked among the whole list from its files per rank key, never
      * holding the round's votes.
      */
    def rateRows(list: RoundList, jurors: Seq[User], chunk: Int = Chunk)(implicit
        ec: ExecutionContext
    ): Source[Seq[String], NotUsed] = {
      val query = list.query
      val key = list.rankKey
      val ranks = Ranks(query.keyCounts(key.columns))
      val pageIds = query.pageIds()
      Source.single(addBom(Seq(header(jurors))).head) ++
        Source.fromIterator(() => pageIds.grouped(chunk))
          .mapAsync(1)(ids => Future(query.withVotes(ids)))
          .mapConcat(_.map(file => row(file, ranks(key.values(file)), jurors, list.round)))
    }

    private def header(jurors: Seq[User]): Seq[String] = Seq("Rank", "File", "Overall") ++ jurors.map(_.fullname)

    private def row(file: ImageWithRating, rank: String, jurors: Seq[User], round: Round): Seq[String] =
      Seq(rank, file.image.title, file.rateString(round)) ++ jurors.map(file.jurorRateStr)

    def addBom(data: Seq[Seq[String]]): Seq[Seq[String]] = {
      val BOM = "\ufeff"
      val header = data.head
      val headerWithBom = (BOM + header.head) +: header.tail
      headerWithBom +: data.tail
    }
  }
