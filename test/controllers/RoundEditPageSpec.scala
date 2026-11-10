package controllers

import db.scalikejdbc.{PlayTestDb, Round, VoteJdbc, User}
import org.intracer.wmua.{ContestJury, Image}
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.imports.{CommonsSource, FakeCommonsSource, FakeFile}

/** The round edit page and the two fragments it loads on demand. */
class RoundEditPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  /** A contest with an admin, two jurors, four images in the contest's category and a
    * binary round holding the first two of them.
    */
  private def fixture(): (ContestJury, User, Round) = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    contestDao.setImagesSource(contest.getId, Some("Category:WLE images"))
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    val jurors = (1 to 2).map(i =>
      userDao.create(User(s"Juror $i", s"juror$i@example.com", None, Set("jury"), contestId = contest.id)))
    val images = (1L to 4L).map(image)
    imageDao.batchInsert(images)
    db.scalikejdbc.CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    val round = roundDao.create(
      Round(None, 1, Some("Round 1"), contest.getId, distribution = 0, active = true))
    for (img <- images.take(2); juror <- jurors)
      VoteJdbc.create(img.pageId, rate = 0, juryId = juror.getId, roundId = round.getId)
    (contest, admin, round)
  }

  private def get(url: String, user: User) =
    FakeRequest(GET, url).withSession(Secured.UserName -> user.email).withCSRFToken

  /** The new-round form as the browser submits it, with its submission token. */
  private def saveNewRound(contestId: Long, jurors: Seq[Long], token: String, user: User,
      extra: Seq[(String, String)] = Nil) =
    FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> user.email)
      .withFormUrlEncodedBody(
        Seq("number" -> "0", "name" -> "Round 2", "contest" -> contestId.toString, "roles" -> "jury",
          "distribution" -> "1", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
          "submitToken" -> token).filterNot(f => extra.exists(_._1 == f._1)) ++ extra ++
          jurors.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }: _*)
      .withCSRFToken

  /** The top-N order select's option values and the selected ones. */
  private def topOrderChoices(html: String): (Seq[String], Seq[String]) = {
    import scala.jdk.CollectionConverters._
    val options = org.jsoup.Jsoup.parse(html).select("select#topBinaryOrder option").asScala.toSeq
    (options.map(_.attr("value")), options.filter(_.hasAttr("selected")).map(_.attr("value")))
  }

  "the new-round form [A-01]" should {

    "carry a fresh submission token" in {
      testDbApp { implicit app =>
        val (contest, admin, _) = fixture()
        def token() = """name="submitToken" value="([0-9a-f-]{36})"""".r
          .findFirstMatchIn(contentAsString(route(app, get(s"/admin/rounds/edit?contestId=${contest.getId}", admin)).get))
          .map(_.group(1))
        val (first, second) = (token(), token())
        first must beSome
        first !== second
      }
    }

    "suggest Commons categories on both category fields (category-suggest.js)" in {
      testDbApp { implicit app =>
        val (contest, admin, _) = fixture()
        val html = contentAsString(route(app, get(s"/admin/rounds/edit?contestId=${contest.getId}", admin)).get)
        html must contain("javascripts/category-suggest.js")
        forall(Seq("source", "excludeCategory")) { id =>
          s"""<input[^>]*id="$id"[^>]*>""".r.findFirstIn(html) must beSome.which(_.contains("""data-category-suggest="category""""))
        }
      }
    }

    "name the rating types Comments only, Yes/No and 1-N rating, on the form and on the rounds list" in {
      testDbApp { implicit app =>
        import scala.jdk.CollectionConverters._
        val (contest, admin, round) = fixture()
        round.rates must_== Round.binaryRound
        roundDao.create(Round(None, 2, Some("Round 2"), contest.getId, rates = Round.commentsOnlyRound))
        roundDao.create(Round(None, 3, Some("Round 3"), contest.getId, rates = Round.ratesById(5)))
        def page(url: String, lang: String = "en") = org.jsoup.Jsoup.parse(contentAsString(route(app,
          FakeRequest(GET, url).withSession(Secured.UserName -> admin.email)
            .withHeaders("Accept-Language" -> lang).withCSRFToken).get))
        def options(form: org.jsoup.nodes.Document) = Seq(0, 1, 5).map(value =>
          form.select(s"select#rates option[value=$value]").asScala.map(_.text).mkString)
        def cells(list: org.jsoup.nodes.Document) = list.select("td").asScala.map(_.text)
        val formUrl = s"/admin/rounds/edit?contestId=${contest.getId}"
        val listUrl = s"/admin/rounds?contestId=${contest.getId}"
        val (form, list) = (page(formUrl), page(listUrl))
        val labels = Seq("Comments only", "Yes/No", "1-5 rating")
        options(form) must_== labels
        forall(labels)(label => cells(list) must contain(label))
        forall(Seq(form, list))(_.text must not(contain("+/-")).and(not(contain("1-0 rating"))))
        // the labels are the message keys', in the page's language
        val ukLabels = Seq("Лише коментарі", "Так/Ні", "Оцінка: 1-5")
        options(page(formUrl, "uk")) must_== ukLabels
        forall(ukLabels)(label => cells(page(listUrl, "uk")) must contain(label))
      }
    }

    "create one round when saved twice [A-15]" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        val token = java.util.UUID.randomUUID().toString

        val first = route(app, saveNewRound(contest.getId, jurors, token, admin)).get
        status(first) must_== SEE_OTHER
        flash(first).get("error") must beNone

        val second = route(app, saveNewRound(contest.getId, jurors, token, admin)).get
        status(second) must_== SEE_OTHER
        flash(second).get("error") must beSome.which(_.contains("already created from this form"))

        roundDao.findByContest(contest.getId).count(_.name.contains("Round 2")) === 1
      }
    }

    "show our own check's refusal of the settings" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        val token = java.util.UUID.randomUUID().toString
        // three jurors per image, of two
        val result = route(app, saveNewRound(contest.getId, jurors, token, admin, Seq("distribution" -> "3"))).get
        status(result) must_== BAD_REQUEST
        contentAsString(result) must contain("Could not create the round: 3 jurors per image, but only 2 jurors selected")
        contentAsString(result) must not(contain("requirement failed"))
      }
    }

    "show any other failure of the creation as a reference, not its text" in {
      val failing = org.mockito.Mockito.mock(classOf[services.RoundService])
      import org.mockito.ArgumentMatchers.any
      org.mockito.Mockito.when(failing.submit(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Duplicate entry 'secret' for key 'round.name'"))
      testDbAppWith(bind[services.RoundService].toInstance(failing)) { implicit app =>
        val (contest, admin, round) = fixture()
        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        val result = route(app, saveNewRound(contest.getId, jurors, java.util.UUID.randomUUID().toString, admin)).get
        status(result) must_== BAD_REQUEST
        val html = contentAsString(result)
        html must contain("Could not create the round. If it happens again, tell the organizers this reference:")
        html must not(contain("Duplicate entry"))
      }
    }

    "keep the number of top files off a new round's form without ?topImages, and refuse one below 1" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        // top-N stays unlinked until its ties are decided (roadmap, the user, 2026-11-05)
        val html = contentAsString(route(app, get(s"/admin/rounds/edit?contestId=${contest.getId}", admin)).get)
        html must not(contain("""name="topImages""""))

        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        val zero = route(app, saveNewRound(contest.getId, jurors, java.util.UUID.randomUUID().toString, admin,
          Seq("previousRound[0]" -> round.getId.toString, "topImages" -> "0"))).get
        status(zero) must_== BAD_REQUEST
      }
    }

    "preselect the default order of a top-N and store it, not the legacy rule's NULL" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        val html = contentAsString(route(app,
          get(s"/admin/rounds/edit?contestId=${contest.getId}&topImages=1", admin)).get)
        topOrderChoices(html) === ((Seq("approvals", "difference", "approvals-only"), Seq("approvals")))

        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        val saved = route(app, saveNewRound(contest.getId, jurors, java.util.UUID.randomUUID().toString, admin,
          Seq("previousRound[0]" -> round.getId.toString, "topImages" -> "1", "topBinaryOrder" -> "approvals"))).get
        status(saved) must_== SEE_OTHER
        // a form without the field (an older page) stores the default too
        val omitted = route(app, saveNewRound(contest.getId, jurors, java.util.UUID.randomUUID().toString, admin,
          Seq("previousRound[0]" -> round.getId.toString, "topImages" -> "1"))).get
        status(omitted) must_== SEE_OTHER
        roundDao.findByContest(contest.getId).filter(_.name.contains("Round 2")).map(_.topBinaryOrder) ===
          Seq(Some(org.intracer.wmua.BinaryOrder.Default), Some(org.intracer.wmua.BinaryOrder.Default))
      }
    }
  }

  "the round edit page [A-02]" should {

    "show the legacy rule of a top-N from before V72 (no order stored), selected" in {
      testDbApp { implicit app =>
        val (contest, admin, previous) = fixture()
        val round = roundDao.create(Round(None, 2, Some("Old top"), contest.getId,
          previous = Some(previous.getId.toString), topImages = Some(1), active = true))
        val html = contentAsString(route(app,
          get(s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}", admin)).get)
        topOrderChoices(html) === ((Seq("approvals", "difference", "approvals-only", "legacy"), Seq("legacy")))
      }
    }

    "save a round stored with a top-N of 0 (legacy) from its own form, the number field shown only for N > 0" in {
      import scala.jdk.CollectionConverters._
      testDbApp { implicit app =>
        val (contest, admin, previous) = fixture()
        val round = roundDao.create(Round(None, 2, Some("Zero top"), contest.getId,
          previous = Some(previous.getId.toString), topImages = Some(0), active = true))
        def page(query: String = "") = org.jsoup.Jsoup.parse(contentAsString(route(app,
          get(s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}$query", admin)).get))
        // the fields a browser sends: inputs (a checkbox only if checked), selects' chosen options, textareas
        def sent(doc: org.jsoup.nodes.Document): Seq[(String, String)] = {
          val form = doc.getElementById("round-form")
          val inputs = form.select("input[name]").asScala.toSeq
            .filterNot(i => Set("checkbox", "radio")(i.attr("type")) && !i.hasAttr("checked"))
            .filterNot(_.attr("type") == "submit")
            .map(i => i.attr("name") -> i.attr("value"))
          val selects = form.select("select[name]").asScala.toSeq.filterNot(_.hasAttr("disabled")).flatMap { s =>
            val chosen = s.select("option[selected]").asScala.toSeq
            (if (chosen.nonEmpty || s.hasAttr("multiple")) chosen else s.select("option").asScala.toSeq.take(1))
              .map(o => s.attr("name") -> o.attr("value"))
          }
          val texts = form.select("textarea[name]").asScala.toSeq.map(t => t.attr("name") -> t.text)
          inputs ++ selects ++ texts
        }
        val stored = page()
        // the round's jurors, as its select has them selected once it has some (this one is new)
        val jurors = User.findRoundJurors(previous.getId).map(_.getId).zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }
        val result = route(app, FakeRequest(POST, "/admin/rounds/save")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody(sent(stored).filterNot(_._1.startsWith("jurors")) ++ jurors: _*).withCSRFToken).get
        (stored.select("[name=topImages]").size === 0) and
          (page("&topImages=0").select("[name=topImages]").size === 0) and
          (page("&topImages=3").select("input[name=topImages][type=number][min=1][value=3]").size === 1) and
          // on a refusal, the form's errors say which field
          (status(result) aka org.jsoup.Jsoup.parse(contentAsString(result))
            .select(".invalid-feedback, .alert-danger").asScala.map(e => e.parent().text).mkString(" | ") must_== SEE_OTHER) and
          (flash(result).get("error") must beNone) and
          // the stored 0 stays as it was: the form only doesn't show it
          (roundDao.findById(round.getId).flatMap(_.topImages) must beSome(0))
      }
    }

    "preserve every saved filter when the edit form omits read-only fields" in {
      testDbApp { implicit app =>
        val (contest, admin, previous) = fixture()
        val jurors = User.findRoundJurors(previous.getId).map(_.getId)
        val round = roundDao.create(Round(None, 2, Some("Filtered"), contest.getId,
          minMpx = Some(4), previous = Some(previous.getId.toString), prevSelectedBy = Some(2),
          prevMinAvgRate = Some(BigDecimal("3.5")), category = Some("Category:Included"),
          excludeCategory = Some("Category:Excluded"), categoryClause = Some("or"),
          regions = Some("12,14"), minImageSize = Some(2),
          monuments = Some("12-345-1"), topImages = Some(10), specialNomination = Some("Saved nomination"),
          mediaType = Some("image"), active = true))
        val before = roundDao.findById(round.getId).get
        before.excludeCategory must beSome("Category:Excluded")
        val save = FakeRequest(POST, "/admin/rounds/save")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody(
            Seq("id" -> round.getId.toString, "number" -> "2", "name" -> "Renamed", "contest" -> contest.getId.toString,
              "roles" -> "jury", "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "",
              "mediaType" -> "all") ++
              jurors.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }: _*)
          .withCSRFToken
        val result = route(app, save).get
        status(result) must_== SEE_OTHER
        flash(result).get("error") must beNone
        // The legacy created_at column updates automatically on any save.
        roundDao.findById(round.getId).map(_.copy(createdAt = before.createdAt)) must
          beSome(before.copy(name = Some("Renamed")))
      }
    }

    "keep a stopped round stopped and a started one started when the form is saved" in {
      testDbApp { implicit app =>
        val (contest, admin, started) = fixture()
        val jurors = User.findRoundJurors(started.getId).map(_.getId)
        val stopped = roundDao.create(Round(None, 2, Some("Stopped"), contest.getId, active = false))
        def save(round: Round, name: String) = {
          val request = FakeRequest(POST, "/admin/rounds/save")
            .withSession(Secured.UserName -> admin.email)
            .withFormUrlEncodedBody(
              Seq("id" -> round.getId.toString, "number" -> round.number.toString, "name" -> name,
                "contest" -> contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "1",
                "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
                jurors.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }: _*)
            .withCSRFToken
          status(route(app, request).get) must_== SEE_OTHER
        }
        save(stopped, "Stopped, renamed")
        save(started, "Started, renamed")
        roundDao.findById(stopped.getId).map(r => (r.name, r.active)) must beSome((Some("Stopped, renamed"), false))
        roundDao.findById(started.getId).map(r => (r.name, r.active)) must beSome((Some("Started, renamed"), true))
      }
    }

    "redistribute with the saved minimum megapixels when the edit form submits an empty filter" in {
      testDbApp { implicit app =>
        val (contest, admin, previous) = fixture()
        val jurors = User.findRoundJurors(previous.getId).map(_.getId)
        val service = app.injector.instanceOf[services.RoundService]
        val categoryId = contestDao.findById(contest.getId).get.categoryId.get
        val initial = image(5).copy(width = 2000, height = 1000)
        imageDao.batchInsert(Seq(initial))
        db.scalikejdbc.CategoryLinkJdbc.addToCategory(categoryId, Seq(initial))
        val round = service.createNewRound(
          Round(None, 0, Some("Filtered"), contest.getId, minMpx = Some(1), active = true), jurors)
        val added = Seq(image(6).copy(width = 2000, height = 1000), image(7))
        imageDao.batchInsert(added)
        db.scalikejdbc.CategoryLinkJdbc.addToCategory(categoryId, added)
        val original = VoteJdbc.byRound(round.getId).head
        VoteJdbc.rate(original.pageId, original.juryId, round.getId, 1)

        val count = route(app, get(s"/admin/rounds/newfiles?id=${round.getId}", admin)).get
        status(count) must_== OK
        (contentAsJson(count) \ "count").as[Long] must_== 1L
        val save = FakeRequest(POST, "/admin/rounds/save")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody(
            Seq("id" -> round.getId.toString, "number" -> round.number.toString, "name" -> "Filtered",
              "contest" -> contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "1",
              "minMpx" -> "", "minSize" -> "", "mediaType" -> "all", "newImages" -> "true") ++
              jurors.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }: _*)
          .withCSRFToken
        val result = route(app, save).get
        status(result) must_== SEE_OTHER
        flash(result).get("error") must beNone
        roundDao.findById(round.getId).flatMap(_.minMpx) must beSome(1)
        val selections = VoteJdbc.byRound(round.getId)
        selections.map(_.pageId).toSet must_== Set(5L, 6L)
        selections.find(s => s.pageId == original.pageId && s.juryId == original.juryId).map(_.rate) must beSome(1)
      }
    }

    "show the round without its stat table or new files count" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        val page = route(app, get(s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}", admin)).get
        status(page) must_== OK
        val html = contentAsString(page)
        html must contain(s"/roundstat/${round.getId}/table")
        html must contain(s"/admin/rounds/newfiles?id=${round.getId}")
        html must contain("value='Round 1'").or(contain("value=\"Round 1\""))
        html must not(contain("/thumb_urls/")) // the stat table isn't rendered
        html must contain("""name="fileList"""")
      }
    }

    "add the pasted files to the round [A-14]" in {
      // Commons as the files are stored; real Commons has other files of these names
      testDbAppWith(bind[CommonsSource].toInstance(new FakeCommonsSource((1L to 4L).map(i => FakeFile(image(i)))))) {
        implicit app =>
        val (contest, admin, round) = fixture()
        round.addUsers(User.findRoundJurors(round.getId).map(u => db.scalikejdbc.RoundUser(round.getId, u.getId, "jury", active = true)))
        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        val save = FakeRequest(POST, "/admin/rounds/save")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody(
            Seq("id" -> round.getId.toString, "number" -> "1", "name" -> "Round 1", "contest" -> contest.getId.toString,
              "roles" -> "jury", "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "",
              "mediaType" -> "all",
              "fileList" -> "Image3.jpg\nhttps://commons.wikimedia.org/wiki/File:Image4.jpg") ++
              jurors.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }: _*)
          .withCSRFToken
        val result = route(app, save).get
        status(result) must_== SEE_OTHER
        flash(result).get("error") must beNone
        VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set(1L, 2L, 3L, 4L)
      }
    }

    "not show a round of another contest" in {
      testDbApp { implicit app =>
        val (_, _, round) = fixture()
        val other = contestDao.create(None, "WLM", 2024, "Poland")
        val otherAdmin =
          userDao.create(User("Other", "other@example.com", None, Set("admin"), contestId = other.id))
        val page = route(app, get(s"/admin/rounds/edit?id=${round.getId}&contestId=${other.getId}", otherAdmin)).get
        status(page) must_== OK
        val html = contentAsString(page)
        html must not(contain("Round 1"))
        html must not(contain(s"/roundstat/${round.getId}/table"))
      }
    }

    "answer a round that doesn't exist with the error page, not its id" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        val missing = (round.getId + 987654).toString
        val jurors = User.findRoundJurors(round.getId).map(_.getId)
        def post(url: String, form: (String, String)*) =
          route(app, FakeRequest(POST, url).withSession(Secured.UserName -> admin.email)
            .withFormUrlEncodedBody(form: _*).withCSRFToken).get
        val results = Seq(
          post("/admin/rounds/save", "id" -> missing, "number" -> "1", "name" -> "Round 1",
            "contest" -> contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "1",
            "minMpx" -> "", "minSize" -> "", "mediaType" -> "all", "jurors[0]" -> jurors.head.toString),
          post("/admin/setround", "currentId" -> missing, "setActive" -> "false"),
          post("/round/setrounduser", "parentId" -> missing, "currentId" -> admin.getId.toString, "setActive" -> "false")
        )
        results.map(status(_)) must_== Seq(NOT_FOUND, NOT_FOUND, NOT_FOUND)
        results.map(contentAsString(_)) must contain(not(contain(missing))).forall
        results.map(contentAsString(_)) must contain(contain("There is no such round.")).forall
      }
    }
  }

  "the round stat table fragment [O-01]" should {
    "list the round's jurors" in {
      testDbApp { implicit app =>
        val (_, admin, round) = fixture()
        val table = route(app, get(s"/roundstat/${round.getId}/table", admin)).get
        status(table) must_== OK
        contentAsString(table) must contain("juror1@example.com")
        contentAsString(table) must contain("/thumb_urls/")
        contentAsString(table) must contain("To download them with wget or a script, send your session cookie.")
        contentAsString(table) must not(contain("<html"))
      }
    }
  }

  "the new files count [A-05]" should {
    "count the contest images the round doesn't have yet" in {
      testDbApp { implicit app =>
        val (_, admin, round) = fixture()
        val count = route(app, get(s"/admin/rounds/newfiles?id=${round.getId}", admin)).get
        status(count) must_== OK
        contentAsJson(count) must_== Json.obj("count" -> 2)
      }
    }

    "be 404 for a round that doesn't exist" in {
      testDbApp { implicit app =>
        val (_, admin, round) = fixture()
        status(route(app, get(s"/admin/rounds/newfiles?id=${round.getId + 100}", admin)).get) must_== NOT_FOUND
        status(route(app, get(s"/roundstat/${round.getId + 100}/table", admin)).get) must_== NOT_FOUND
      }
    }

    "refuse an admin of another contest" in {
      testDbApp { implicit app =>
        val (_, _, round) = fixture()
        val other = contestDao.create(None, "WLM", 2024, "Poland")
        val otherAdmin =
          userDao.create(User("Other", "other@example.com", None, Set("admin"), contestId = other.id))
        val count = route(app, get(s"/admin/rounds/newfiles?id=${round.getId}", otherAdmin)).get
        status(count) must_== FORBIDDEN
        val table = route(app, get(s"/roundstat/${round.getId}/table", otherAdmin)).get
        status(table) must_== FORBIDDEN
      }
    }
  }
}
