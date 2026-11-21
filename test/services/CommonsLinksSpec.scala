package services

import org.specs2.mutable.Specification
import play.api.Configuration

class CommonsLinksSpec extends Specification {

  "the enable-email preload page" should {
    "default to a subpage of the tool's Commons page" in {
      CommonsLinks.default.enableEmailPreload === "Commons:WLX Jury Tool/enableEmailText"
      CommonsLinks.default.enableEmailUrl("Juror_A") ===
        "https://commons.wikimedia.org/w/index.php?title=User_talk:Juror_A&action=edit&section=new" +
        "&preload=Commons%3AWLX_Jury_Tool%2FenableEmailText&preloadtitle=Please%20enable%20wiki%20email"
    }

    "take the setting, URL-encoded" in {
      val links = new CommonsLinks(Configuration("wlxjury.commons.enableEmailPreload" -> " User:Example/Text & more "))
      links.enableEmailPreload === "User:Example/Text & more"
      links.enableEmailUrl("Juror_A") must contain("&preload=User%3AExample%2FText_%26_more&")
    }

    "fall back to the default for an empty setting" in {
      new CommonsLinks(Configuration("wlxjury.commons.enableEmailPreload" -> " ")).enableEmailPreload ===
        CommonsLinks.DefaultEnableEmailPreload
    }

    "be injected with the configured page" in {
      val injector = new play.api.inject.guice.GuiceInjectorBuilder()
        .bindings(play.api.inject.bind[Configuration].toInstance(
          Configuration("wlxjury.commons.enableEmailPreload" -> "Commons:Other page/Text")))
        .injector()
      injector.instanceOf[CommonsLinks].enableEmailUrl("Juror_A") must contain("&preload=Commons%3AOther_page%2FText&")
    }
  }

  "the User-Agent towards Wikimedia" should {
    import LocalImageCacheService.{ToolPage, userAgentValue}

    "name only the tool's page without a contact" in {
      userAgentValue(None) === s"WLXJury/1.0 ($ToolPage)"
      userAgentValue(Some("  ")) === s"WLXJury/1.0 ($ToolPage)"
    }

    "add the configured contact" in {
      userAgentValue(Some(" operator@example.org ")) === s"WLXJury/1.0 ($ToolPage; operator@example.org)"
    }

    "refuse a contact with a line break" in {
      userAgentValue(Some("a@example.org\r\nX-Injected: 1")) must throwA[IllegalArgumentException]
    }
  }
}
