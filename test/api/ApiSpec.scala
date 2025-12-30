package api

import com.github.pjfanning.pekkohttpplayjson.PlayJsonSupport._
import org.apache.pekko.http.scaladsl.testkit.Specs2RouteTest
import org.intracer.wmua.ContestJury
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.ContestService

class ApiSpec
  extends Specification
  with Mockito
  with Specs2RouteTest
  with JsonFormat {

  private val service = mock[ContestService]
  private val route = new Api(service).routes
  private val contest = ContestJury(
    id = Some(1),
    name = "WLM",
    year = 2022,
    country = "Ukraine",
    images = Some("Category:Images_from_Wiki_Loves_Monuments_2022_in_Ukraine")
  )

  "api" should {
    "get contests" in {
      service.findContests() returns List(contest)
      Get("api/contests") ~> route ~> check {
        responseAs[List[ContestJury]] === List(contest)
      }
    }
  }

}
