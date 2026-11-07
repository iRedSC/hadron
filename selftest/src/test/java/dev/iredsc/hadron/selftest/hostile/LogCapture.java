package dev.iredsc.hadron.selftest.hostile;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Every line the server logs while it is open, with its logger, level and stack trace, so a test can read what an
 * owner reads in the log. Errors that are only logged (a throwing listener, task or onDisable) are checked here.
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final LoggerContext context;

    private LogCapture(LoggerContext context) {
        super("HadronSelfTestHostileCapture", null, null, true, Property.EMPTY_ARRAY);
        this.context = context;
    }

    static LogCapture open() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LogCapture capture = new LogCapture(context);
        capture.start();
        context.getConfiguration().getRootLogger().addAppender(capture, null, null);
        context.updateLoggers();
        return capture;
    }

    @Override
    public void append(LogEvent event) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(event.getLoggerName()).append("] ").append(event.getLevel()).append(' ')
                .append(event.getMessage().getFormattedMessage());
        if (event.getThrown() != null) {
            StringWriter stack = new StringWriter();
            event.getThrown().printStackTrace(new PrintWriter(stack));
            line.append('\n').append(stack);
        }
        lines.add(line.toString());
    }

    /** The lines logged so far that contain every one of the given texts. */
    List<String> matching(String... texts) {
        return lines.stream().filter(l -> {
            for (String t : texts) if (!l.contains(t)) return false;
            return true;
        }).toList();
    }

    @Override
    public void close() {
        context.getConfiguration().getRootLogger().removeAppender(getName());
        context.updateLoggers();
        stop();
    }
}
