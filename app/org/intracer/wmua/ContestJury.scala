package org.intracer.wmua

import controllers.Greeting

case class ContestJury(id: Option[Long],
                       name: String,
                       year: Int,
                       country: String,
                       images: Option[String],
                       categoryId: Option[Long] = None,
                       currentRound: Option[Long] = None,
                       monumentIdTemplate: Option[String] = None,
                       greeting: Greeting = Greeting(None, use = true),
                       campaign: Option[String] = None,
                       /** contest_jury.series_id (V109): the contest's series, if assigned. */
                       seriesId: Option[Long] = None,
                       /** contest_jury.retired_at IS NOT NULL (V109): its members have no access, and menus
                         * and lists leave it out (contest series plan §3.1).
                         */
                       retired: Boolean = false) extends HasId {
  //def localName = Messages("wiki.loves.earth." + country, year)(messages)
  def fullName = s"$name $year in $country"

  def getImages: String = images.getOrElse("Category:Images from " + name)
}
