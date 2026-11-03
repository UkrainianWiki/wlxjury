package db.scalikejdbc

import play.api.i18n.{Lang, Messages}
import play.i18n

/** Messages of a fixed dictionary; a missing key throws. */
case class MessageStub(dict: Map[String, String]) extends Messages {

  override def apply(key: String, args: Any*): String =
    if (args.isEmpty) dict(key) else java.text.MessageFormat.format(dict(key), args.map(_.asInstanceOf[AnyRef]): _*)

  override def apply(keys: Seq[String], args: Any*): String = ???

  override def lang: Lang = ???

  override def translate(key: String, args: Seq[Any]): Option[String] = ???

  override def isDefinedAt(key: String): Boolean = dict.contains(key)

  override def asJava: i18n.Messages = ???
}
