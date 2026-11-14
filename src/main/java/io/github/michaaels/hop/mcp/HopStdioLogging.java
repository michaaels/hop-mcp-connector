package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;

/** Rebind console managers initialized by Hop before this command took ownership of stdout. */
final class HopStdioLogging {
  private HopStdioLogging() {}

  static void redirectConsoleAppenders() {
    if (!(LogManager.getFactory() instanceof Log4jContextFactory factory)) return;
    var contexts = new ArrayList<>(factory.getSelector().getLoggerContexts());
    if (LogManager.getContext(false) instanceof LoggerContext current
        && !contexts.contains(current)) contexts.add(current);
    for (LoggerContext context : contexts) redirect(context);
  }

  private static void redirect(LoggerContext context) {
    var configuration = context.getConfiguration();
    var loggers = new ArrayList<>(configuration.getLoggers().values());
    loggers.add(configuration.getRootLogger());
    for (var appender : new ArrayList<>(configuration.getAppenders().values())) {
      if (!(appender instanceof ConsoleAppender console)
          || console.getTarget() != ConsoleAppender.Target.SYSTEM_OUT) continue;
      var replacement =
          ConsoleAppender.newBuilder()
              .setName(console.getName())
              .setTarget(ConsoleAppender.Target.SYSTEM_ERR)
              .setFollow(true)
              .setLayout(console.getLayout())
              .setFilter(console.getFilter())
              .setIgnoreExceptions(console.ignoreExceptions())
              .build();
      replacement.start();
      configuration.addAppender(replacement);
      for (var logger : loggers) {
        if (!logger.getAppenders().containsKey(console.getName())) continue;
        org.apache.logging.log4j.Level level = null;
        Filter filter = null;
        for (var reference : logger.getAppenderRefs()) {
          if (console.getName().equals(reference.getRef())) {
            level = reference.getLevel();
            filter = reference.getFilter();
            break;
          }
        }
        logger.removeAppender(console.getName());
        logger.addAppender(replacement, level, filter);
      }
      context.updateLoggers();
      console.stop();
    }
  }
}
