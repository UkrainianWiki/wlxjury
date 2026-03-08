package controllers

import db.scalikejdbc.TestDb
import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test.FakeRequest
import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, File}
import java.nio.file.Files
import javax.imageio.ImageIO

class ImageProxyControllerSpec extends Specification with TestDb {

  sequential

  // Writes a minimal valid JPEG to the given file (creates parent dirs).
  private def writeJpeg(file: File, w: Int = 10, h: Int = 10): Unit = {
    file.getParentFile.mkdirs()
    val img  = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "JPEG", baos)
    Files.write(file.toPath, baos.toByteArray)
  }

  // Deletes a directory tree (best-effort, used for test cleanup).
  private def deleteDir(dir: File): Unit =
    if (dir.exists()) {
      Files.walk(dir.toPath)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }

  "ImageProxyController" should {

    "return 404 for a path not matching the thumb pattern" in {
      testDbApp { implicit app =>
        val result = route(app,
          FakeRequest("GET", "/wikipedia/commons/thumb/bad")).get
        status(result) must beOneOf(NOT_FOUND, BAD_REQUEST)
      }
    }

    "redirect to Wikimedia when image is not in DB" in {
      testDbApp { implicit app =>
        val result = route(app,
          FakeRequest("GET",
            "/wikipedia/commons/thumb/x/xx/NoSuchFile.jpg/250px-NoSuchFile.jpg")).get
        status(result) mustEqual SEE_OTHER
        header("Location", result) must beSome(contain("upload.wikimedia.org"))
      }
    }

    // Wikimedia URLs use underscores while DB titles store spaces.
    // e.g. URL: /My_Image.jpg  →  DB title: File:My Image.jpg
    "serve cached image when URL uses underscores but DB title has spaces" in {
      val tmpDir = Files.createTempDirectory("proxy-underscore-test").toFile

      // image.url uses underscores (as stored in DB from Wikimedia API)
      val image = Image(pageId = 100L, title = "File:My Image.jpg",
        url = Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/My_Image.jpg"),
        width = 800, height = 600)

      // Pre-create the cached file at the path LocalImageCacheService.localFile would produce:
      //   /wikipedia/commons/thumb/a/ab/My_Image.jpg/333px-My_Image.jpg
      // (333 = ImageUtil.resizeTo(800, 600, 250), the width scaled to a 250px-tall box)
      val cachedFile = new File(tmpDir,
        "wikipedia/commons/thumb/a/ab/My_Image.jpg/333px-My_Image.jpg")
      writeJpeg(cachedFile)

      implicit val cfg: Map[String, String] =
        Map("wlxjury.thumbs.local-path" -> tmpDir.getAbsolutePath)

      try {
        testDbApp { implicit app =>
          imageDao.batchInsert(Seq(image))

          // Registry startup scan is a background Future over a single-file dir;
          // it completes in <10 ms in practice, 500 ms is a very conservative wait.
          Thread.sleep(500)

          // Request uses underscore (standard Wikimedia URL encoding)
          val result = route(app,
            FakeRequest("GET",
              "/wikipedia/commons/thumb/a/ab/My_Image.jpg/333px-My_Image.jpg")).get

          status(result)      mustEqual OK
          contentType(result) must beSome("image/jpeg")
        }
      } finally {
        deleteDir(tmpDir)
      }
    }

    "redirect to Wikimedia when URL uses underscores but no matching image exists in DB" in {
      testDbApp { implicit app =>
        // No image inserted — DB is empty.
        // The underscore→space normalisation does not create a false match.
        val result = route(app,
          FakeRequest("GET",
            "/wikipedia/commons/thumb/a/ab/No_Such_Image.jpg/250px-No_Such_Image.jpg")).get
        status(result) mustEqual SEE_OTHER
        header("Location", result) must beSome(contain("upload.wikimedia.org"))
      }
    }

    "include /wikipedia/commons/thumb/ in the Wikimedia redirect URL" in {
      testDbApp { implicit app =>
        val result = route(app,
          FakeRequest("GET",
            "/wikipedia/commons/thumb/x/xx/Missing.jpg/120px-Missing.jpg")).get
        status(result) mustEqual SEE_OTHER
        header("Location", result) must beSome(
          beEqualTo("https://upload.wikimedia.org/wikipedia/commons/thumb/x/xx/Missing.jpg/120px-Missing.jpg"))
      }
    }
  }
}
