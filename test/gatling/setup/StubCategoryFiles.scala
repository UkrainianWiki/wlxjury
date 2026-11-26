package gatling.setup

import db.ImageRepo
import org.intracer.wmua.cmd.DistributeImages
import scalikejdbc._

import javax.inject.Inject

/** A round's category filter for an offline measurement (`-Dgatling.categoryStub=true`,
  * [[GatlingTestFixture]]): a category's files are the fixture's members of the category of that
  * name (`category_members`), any other name none; never Commons. Only the category read differs.
  */
class StubCategoryFiles @Inject() (imageRepo: ImageRepo) extends DistributeImages(imageRepo) {

  override def categoryFileIds(maybeCategory: Option[String]): Iterable[Long] =
    maybeCategory.map(_.trim).filter(_.nonEmpty).fold[Iterable[Long]](Nil) { category =>
      val name = category.stripPrefix("Category:").replace(' ', '_')
      DB.readOnly { implicit s =>
        sql"""SELECT m.page_id FROM category_members m JOIN category c ON c.id = m.category_id
              WHERE c.title IN ($category, ${"Category:" + name.replace('_', ' ')}, ${name.replace('_', ' ')})"""
          .map(_.long(1)).list()
      }
    }
}
