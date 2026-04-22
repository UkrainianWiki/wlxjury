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
                       campaign: Option[String] = None) extends HasId {
  //def localName = Messages("wiki.loves.earth." + country, year)(messages)
  def fullName = s"$name $year in $country"

  def getImages: String = images.getOrElse("Category:Images from " + name)
}
