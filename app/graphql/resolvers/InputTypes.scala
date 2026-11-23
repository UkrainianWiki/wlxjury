package graphql.resolvers

import sangria.schema._
import sangria.schema.InputObjectType.DefaultInput

object InputTypes {

  // ── Contest ──────────────────────────────────────────────────────────────

  val ContestInputType: InputObjectType[DefaultInput] = InputObjectType(
    "ContestInput",
    List(
      InputField("name",               StringType),
      InputField("year",               IntType),
      InputField("country",            StringType),
      InputField("images",             OptionInputType(StringType)),
      InputField("monumentIdTemplate", OptionInputType(StringType)),
      InputField("campaign",           OptionInputType(StringType))
    )
  )

  // ── Round ─────────────────────────────────────────────────────────────────

  val RoundInputType: InputObjectType[DefaultInput] = InputObjectType(
    "RoundInput",
    List(
      InputField("contestId",       IDType),
      InputField("name",            OptionInputType(StringType)),
      InputField("distribution",    OptionInputType(IntType)),
      InputField("minMpx",          OptionInputType(IntType)),
      InputField("category",        OptionInputType(StringType)),
      InputField("regions",         OptionInputType(StringType)),
      InputField("mediaType",       OptionInputType(StringType)),
      InputField("hasCriteria",     OptionInputType(BooleanType)),
      InputField("previousRoundId", OptionInputType(IDType)),
      InputField("prevSelectedBy",  OptionInputType(IntType))
    )
  )

  // ── User ──────────────────────────────────────────────────────────────────

  val UserInputType: InputObjectType[DefaultInput] = InputObjectType(
    "UserInput",
    List(
      InputField("fullname",    StringType),
      InputField("email",       StringType),
      InputField("roles",       ListInputType(StringType)),
      InputField("contestId",   OptionInputType(IDType)),
      InputField("lang",        OptionInputType(StringType)),
      InputField("wikiAccount", OptionInputType(StringType))
    )
  )

  // ── Rating ────────────────────────────────────────────────────────────────

  val RatingInputType: InputObjectType[DefaultInput] = InputObjectType(
    "RatingInput",
    List(
      InputField("pageId", IDType),
      InputField("rate",   IntType)
    )
  )

  // ── Helpers ───────────────────────────────────────────────────────────────

  def str(m: DefaultInput, key: String): String =
    m(key).asInstanceOf[String]

  def int(m: DefaultInput, key: String): Int =
    m(key).asInstanceOf[Int]

  def bool(m: DefaultInput, key: String): Boolean =
    m(key).asInstanceOf[Boolean]

  def optStr(m: DefaultInput, key: String): Option[String] =
    m.get(key).flatMap(Option(_)).map(_.asInstanceOf[String])

  def optInt(m: DefaultInput, key: String): Option[Int] =
    m.get(key).flatMap(Option(_)).map(_.asInstanceOf[Int])

  def optBool(m: DefaultInput, key: String): Option[Boolean] =
    m.get(key).flatMap(Option(_)).map(_.asInstanceOf[Boolean])

  def strList(m: DefaultInput, key: String): List[String] =
    m(key).asInstanceOf[Seq[String]].toList
}
