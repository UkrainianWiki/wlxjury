package org.intracer.wmua

/** A region of the region nav: a monument id prefix, its name and file count if known.
  *
  * @param kind a country's region, a special nomination's id prefix, or neither
  */
case class Region(id: String, name: String, count: Option[Int], kind: Region.Kind = Region.Geographic) {

  /** Whether a file of that monument id is in this region: by its prefix
    * ([[db.scalikejdbc.rewrite.ImageDbNew.regionOf]]), or a sub-region's (`07-101`) start.
    */
  def holds(monumentId: String): Boolean =
    if (id.contains("-")) monumentId.startsWith(id) else Region.of(monumentId).contains(id)
}

object Region {

  sealed trait Kind
  case object Geographic extends Kind
  case object Nomination extends Kind
  case object Other extends Kind

  /** The region of a monument id, as [[db.scalikejdbc.rewrite.ImageDbNew.regionOf]] has it in
    * SQL: up to three characters before the first dash, none if empty.
    */
  def of(monumentId: String): Option[String] =
    Option(monumentId.takeWhile(_ != '-').take(3)).filter(_.nonEmpty)
}
