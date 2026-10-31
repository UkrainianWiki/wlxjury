package org.intracer.wmua

import java.net.URLClassLoader
import org.specs2.mutable.Specification
import scala.util.Try

class CategoryOperationInitializationSpec extends Specification {
  private val prefix = "org.intracer.wmua.CategoryOperation"
  private val names = Seq("none", "include", "exclude", "and", "or", "minus")

  // Each example starts with a fresh copy of this class family. Other specs can
  // initialize the companion first and conceal failures on a production JVM.
  private def freshOperations() = new URLClassLoader(
    Array(classOf[CategoryOperation].getProtectionDomain.getCodeSource.getLocation), getClass.getClassLoader) {
    override protected def loadClass(name: String, resolve: Boolean): Class[_] = synchronized {
      if (name == prefix || name.startsWith(prefix + "$")) {
        val loaded = Option(findLoadedClass(name)).getOrElse(findClass(name))
        if (resolve) resolveClass(loaded)
        loaded
      } else super.loadClass(name, resolve)
    }
  }

  Seq("Unrestricted", "Include", "Exclude", "And", "Union", "Minus", "companion").foreach { first =>
      s"category operations support every lookup when $first is accessed first" in {
        val loader = freshOperations()
        try {
          def module(name: String): AnyRef =
            Class.forName(name, true, loader).getField("MODULE$").get(null)
          if (first != "companion") module(prefix + "$" + first + "$")
          val companion = module(prefix + "$")
          def value(operation: AnyRef): Option[String] = Option(operation).map { op =>
            op.getClass.getMethod("value").invoke(op).asInstanceOf[String]
          }
          val values = companion.getClass.getMethod("values").invoke(companion).asInstanceOf[Seq[AnyRef]]
          def lookup(method: String, input: String): Option[String] =
            companion.getClass.getMethod(method, classOf[String]).invoke(companion, input)
              .asInstanceOf[Option[AnyRef]].flatMap(value)
          val lookups = Try {
            (names.map(lookup("fromForm", _)), names.map(lookup("fromStored", _)),
              Seq("1", "-1", "0").map(lookup("fromStored", _)))
          }.toEither
          (values.map(value) === names.map(Some(_))) and
            (lookups === Right((names.map(Some(_)), names.map(Some(_)),
              Seq(Some("include"), Some("exclude"), Some("exclude")))))
        } finally loader.close()
      }
  }
}
