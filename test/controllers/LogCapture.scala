package controllers

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.read.ListAppender

import scala.jdk.CollectionConverters._

/** The lines logged on some loggers while a block runs, by logger name, at any level (the
  * loggers are set to DEBUG meanwhile and restored after).
  */
object LogCapture {

  def apply[T](names: String*)(f: => T): (T, Map[String, Seq[String]]) = {
    val attached = names.map { name =>
      val logger = org.slf4j.LoggerFactory.getLogger(name).asInstanceOf[Logger]
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      val level = logger.getLevel
      logger.setLevel(Level.DEBUG)
      logger.addAppender(appender)
      (name, logger, appender, level)
    }
    try {
      val result = f
      (result, attached.map { case (name, _, appender, _) =>
        name -> appender.list.asScala.toSeq.map(_.getFormattedMessage)
      }.toMap)
    } finally attached.foreach { case (_, logger, appender, level) =>
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
  }
}
