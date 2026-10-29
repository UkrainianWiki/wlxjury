package controllers

import org.specs2.mutable.Specification
import play.api.http.{DefaultFileMimeTypes, DefaultHttpErrorHandler, FileMimeTypesConfiguration}
import play.api.test.FakeRequest
import play.api.{Environment, Mode}

import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Asset URLs change with every deploy that changes the file: sbt-digest packages a
  * `<name>.md5` beside each asset (only in `sbt dist`/`stage`, so not on the test classpath),
  * `routes.Assets.versioned` names the asset `<md5>-<name>` when that file exists, and
  * `Assets.versioned` caches such a name for a year, a plain name for an hour.
  */
class AssetDigestSpec extends Specification {

  val md5 = "0123456789abcdef0123456789abcdef"

  /** A packaged /public with one digested script and one plain image. */
  lazy val public: Path = {
    val dir = Files.createTempDirectory("assets").resolve("public")
    Files.createDirectories(dir.resolve("javascripts"))
    Files.createDirectories(dir.resolve("images"))
    Files.write(dir.resolve("javascripts/app.js"), "var x;".getBytes(UTF_8))
    Files.write(dir.resolve("javascripts/app.js.md5"), md5.getBytes(UTF_8))
    Files.write(dir.resolve("images/star.png"), Array[Byte](1, 2, 3))
    dir
  }

  def resource(name: String): Option[URL] =
    name.stripPrefix("/public/") match {
      case rel if rel != name && Files.isRegularFile(public.resolve(rel)) => Some(public.resolve(rel).toUri.toURL)
      case _                                                               => None
    }

  // the production settings: AssetsConfiguration.fromConfiguration in Mode.Prod
  lazy val meta = new DefaultAssetsMetadata(
    AssetsConfiguration(enableCacheControl = true),
    resource _,
    new DefaultFileMimeTypes(FileMimeTypesConfiguration(Map("js" -> "text/javascript", "png" -> "image/png")))
  )
  lazy val assets = new Assets(new DefaultHttpErrorHandler(), meta, Environment.simple(mode = Mode.Prod))

  def cacheControl(file: String): Option[String] = {
    val result = Await.result(assets.versioned("/public", Assets.Asset(file))(FakeRequest()), 10.seconds)
    result.header.status === 200
    result.header.headers.get("Cache-Control")
  }

  "a packaged asset" should {
    "be named by its digest where one exists, as the reverse router does" in {
      meta.finder.findAssetPath("/public", "/public/javascripts/app.js") === s"javascripts/$md5-app.js"
      meta.finder.findAssetPath("/public", "/public/images/star.png") === "images/star.png"
      // raty's images path: a directory has no digest
      meta.finder.findAssetPath("/public", "/public/images") === "images"
    }

    "be cached for a year under its digested name" in {
      cacheControl(s"javascripts/$md5-app.js") must beSome("public, max-age=31536000, immutable")
    }

    "be cached for an hour under its plain name" in {
      cacheControl("javascripts/app.js") must beSome("public, max-age=3600")
      cacheControl("images/star.png") must beSome("public, max-age=3600")
    }
  }

  "the templates" should {
    "link assets by the versioned route only" in {
      val templates = Files.walk(Paths.get("app", "views")).iterator().asScala
        .filter(_.toString.endsWith(".scala.html")).toSeq
      templates must not(beEmpty)
      val offending = templates.filter { t =>
        val source = new String(Files.readAllBytes(t), UTF_8)
        source.contains("Assets.at(") || source.contains("\"/assets/") || source.contains("{/assets/")
      }
      offending must beEmpty
    }
  }
}
