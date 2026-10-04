package org.booklore.service.logs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads Trove's own log file (logging.file.name, written in the pattern set in application.yaml)
 * and its rotated archives, so admins can read the log in Settings instead of on the server.
 */
@Service
@Slf4j
public class ServerLogService {

    public static final int MAX_ENTRIES = 5000;
    // 2026-10-04T13:50:10.799+13:00  INFO [main] o.b.BookloreApplication : message
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2}))\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\[([^\\]]*)]\\s+(\\S+)\\s+:\\s?(.*)$");
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private final Environment environment;

    public ServerLogService(Environment environment) {
        this.environment = environment;
    }

    public record LogFile(String name, long sizeBytes, Instant modified, boolean current) {
    }

    /** One log event; {@code detail} holds any lines after the first, such as a stack trace. */
    public record LogEntry(String timestamp, String level, String thread, String logger, String message, String detail) {
    }

    public record LogPage(String file, List<LogEntry> entries, int matched, boolean truncated) {
    }

    /** The current log file first, then the archives, newest first. Empty if file logging is off. */
    public List<LogFile> files() throws IOException {
        Path current = currentFile();
        if (current == null || current.getParent() == null || !Files.isDirectory(current.getParent())) {
            return List.of();
        }
        String base = current.getFileName().toString();
        List<LogFile> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(current.getParent())) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(base)).toList()) {
                result.add(new LogFile(path.getFileName().toString(), Files.size(path),
                        Files.getLastModifiedTime(path).toInstant(), path.equals(current)));
            }
        }
        result.sort(Comparator.comparing(LogFile::current).reversed().thenComparing(LogFile::modified, Comparator.reverseOrder()));
        return result;
    }

    /**
     * The last {@code limit} entries of a log file at or above {@code minLevel} whose text contains
     * {@code query} (case-insensitive), newest first.
     */
    public LogPage read(String fileName, String minLevel, String query, int limit) throws IOException {
        Path file = resolve(fileName);
        int max = Math.clamp(limit, 1, MAX_ENTRIES);
        int threshold = minLevel == null || minLevel.isBlank() ? 0 : Math.max(0, LEVELS.indexOf(minLevel.toUpperCase(Locale.ROOT)));
        String needle = query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);

        Deque<LogEntry> kept = new ArrayDeque<>();
        int[] matched = {0};
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(file), StandardCharsets.UTF_8))) {
            Builder current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = LINE.matcher(line);
                if (m.matches()) {
                    keep(current, threshold, needle, max, kept, matched);
                    current = new Builder(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
                } else if (current != null) {
                    current.detail.append(current.detail.isEmpty() ? "" : "\n").append(line);
                } else if (!line.isBlank()) {
                    // A line before the first recognised one, e.g. written with an older pattern.
                    current = new Builder(null, "INFO", "", "", line);
                }
            }
            keep(current, threshold, needle, max, kept, matched);
        }
        List<LogEntry> newestFirst = new ArrayList<>(kept);
        java.util.Collections.reverse(newestFirst);
        return new LogPage(file.getFileName().toString(), newestFirst, matched[0], matched[0] > kept.size());
    }

    /** The path of a log file named by {@link #files}, for downloading. */
    public Path resolve(String fileName) throws IOException {
        Path current = currentFile();
        if (current == null) {
            throw new NoSuchFileException("File logging is turned off");
        }
        if (fileName == null || fileName.isBlank()) {
            return current;
        }
        for (LogFile file : files()) {
            if (file.name().equals(fileName)) {
                return current.resolveSibling(fileName);
            }
        }
        throw new NoSuchFileException(fileName);
    }

    private Path currentFile() {
        String name = environment.getProperty("logging.file.name");
        return name == null || name.isBlank() ? null : Path.of(name).toAbsolutePath().normalize();
    }

    private static InputStream open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        return file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    private static void keep(Builder entry, int threshold, String needle, int max, Deque<LogEntry> kept, int[] matched) {
        if (entry == null || LEVELS.indexOf(entry.level) < threshold) {
            return;
        }
        if (needle != null && !entry.text().contains(needle)) {
            return;
        }
        matched[0]++;
        kept.addLast(entry.build());
        if (kept.size() > max) {
            kept.removeFirst();
        }
    }

    private static final class Builder {
        final String timestamp;
        final String level;
        final String thread;
        final String logger;
        final String message;
        final StringBuilder detail = new StringBuilder();

        Builder(String timestamp, String level, String thread, String logger, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.thread = thread;
            this.logger = logger;
            this.message = message;
        }

        String text() {
            return (logger + " " + thread + " " + message + "\n" + detail).toLowerCase(Locale.ROOT);
        }

        LogEntry build() {
            return new LogEntry(timestamp, level, thread, logger, message, detail.isEmpty() ? null : detail.toString());
        }
    }
}
