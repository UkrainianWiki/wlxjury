package org.intracer.wmua.cmd

import org.intracer.wmua.{Image, ImageWithRating}
import org.specs2.mutable.Specification

/** Builds the filter through scalawiki's `ContestStat` and `SpecialNomination.getMonumentsMap`,
  * the path that links scalawiki-wlx's stat reports, without the network: a nomination that has
  * neither pages nor cities selects its files by template, so it has no monument ids. It guards the
  * build's exclusion of scalawiki's chart jars (runtime jars investigation, 2026-11-04).
  */
class SpecialNominationFilterSpec extends Specification {

  private def withMonument(pageId: Long, monumentId: String) =
    ImageWithRating(Image(pageId, s"File:$pageId.jpg", monumentId = Some(monumentId)), Nil)

  "SpecialNominationFilter" should {
    "build for a nomination without pages or cities, with no monument ids" in {
      val filter = SpecialNominationFilter("Плівка 2022")
      (filter.specialNominationIds must beEmpty) and
        (filter()(Seq(withMonument(1, "80-361-0001"))) must beEmpty)
    }

    "have no monument ids for an unknown nomination" in {
      SpecialNominationFilter("no such nomination").specialNominationIds must beEmpty
    }
  }
}
