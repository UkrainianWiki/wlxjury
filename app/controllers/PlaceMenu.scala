package controllers

import play.api.i18n.Messages
import play.api.libs.json.{JsObject, Json}
import services.places.{Place, PlaceScheme}

/** A gallery's menu of the places its files are in, regions or countries (regionMenu.scala.html,
  * region-menu.js, in the navbar; one template and one script for every page with places,
  * [[PlaceScheme]]): the place shown (its name; None: all), the list in all of them and in this
  * one, and the URL of the places with their files' counts, read when the menu is first opened
  * ([[PlaceMenu.json]]).
  *
  * @param id       the menu's element id
  * @param label    its label's message key
  * @param allLabel the message key of all the places
  */
final case class RegionMenu(
    id: String,
    label: String,
    allLabel: String,
    current: Option[String],
    allUrl: String,
    currentUrl: String,
    optionsUrl: String
)

object RegionMenu {

  def regions(current: Option[String], allUrl: String, currentUrl: String, optionsUrl: String): RegionMenu =
    RegionMenu("regionmenu", "region.menu", "region.all", current, allUrl, currentUrl, optionsUrl)

  def countries(current: Option[String], allUrl: String, currentUrl: String, optionsUrl: String): RegionMenu =
    RegionMenu("countrymenu", "country", "country.all", current, allUrl, currentUrl, optionsUrl)

  /** The menu of the contest's kind of places ([[PlaceScheme]]): `current` is the place shown,
    * its id (a region's, named here; a country's name).
    */
  def of(scheme: PlaceScheme, current: Option[String], allUrl: String, currentUrl: String, optionsUrl: String)(implicit
      messages: Messages
  ): RegionMenu =
    scheme match {
      case r: PlaceScheme.Regions   => regions(current.map(r.scheme.region(_, None).name), allUrl, currentUrl, optionsUrl)
      case _: PlaceScheme.Countries => countries(current, allUrl, currentUrl, optionsUrl)
    }
}

object PlaceMenu {

  /** The message key of the scheme's kind of place, as its menu's label: "Country", "Region". */
  def placeLabel(scheme: PlaceScheme): String = scheme match {
    case _: PlaceScheme.Countries => "country"
    case _                        => "region.menu"
  }

  /** A place menu's places as JSON (region-menu.js): the place shown (its id; None: all), all
    * of them, and each with its files' count, if counted, its kind and its list's URL (`url`,
    * from the place's id).
    */
  def json(current: Option[String], allName: String, allUrl: String, places: Seq[Place], url: String => String)
      : JsObject =
    Json.obj(
      "current" -> current,
      "all" -> Json.obj("name" -> allName, "url" -> allUrl),
      "regions" -> places.map(p => Json.obj("id" -> p.id, "name" -> p.name, "count" -> p.count, "kind" -> p.kind,
        "url" -> url(p.id)))
    )
}
