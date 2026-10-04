package org.booklore.service.logs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerLogServiceTest {

    private static final String LOG = """
            2026-10-04T13:50:10.799+13:00  INFO [main] o.b.BookloreApplication : Starting Trove
            2026-10-04T13:51:00.001+13:00  INFO [virtual-12] o.b.t.t.FixEpubCoversTask : FIX_EPUB_COVERS: Repaired bookId=6814 (no cover is declared): /srv/trove/library/Exile's Return.epub
            2026-10-04T13:51:01.002+13:00  WARN [virtual-12] o.b.t.t.FixEpubCoversTask : FIX_EPUB_COVERS: Couldn't repair bookId=5 (no cover is declared): Trove has no cover for this book either
            2026-10-04T13:52:00.000+13:00 ERROR [http-nio-6060-exec-1] o.b.s.BookService : Something broke
            java.lang.IllegalStateException: boom
            \tat org.booklore.Foo.bar(Foo.java:1)
            2026-10-04T13:53:00.000+13:00 DEBUG [main] o.b.Quiet : chatter
            """;

    @TempDir
    Path dir;

    ServerLogService service;
    Path logFile;

    @BeforeEach
    void setUp() throws IOException {
        logFile = dir.resolve("trove.log");
        Files.writeString(logFile, LOG);
        service = new ServerLogService(new MockEnvironment().withProperty("logging.file.name", logFile.toString()));
    }

    @Test
    void readsEntriesNewestFirstWithStackTracesAttached() throws IOException {
        ServerLogService.LogPage page = service.read(null, null, null, 100);

        assertThat(page.entries()).hasSize(5);
        assertThat(page.entries().getFirst().message()).isEqualTo("chatter");
        ServerLogService.LogEntry error = page.entries().get(1);
        assertThat(error.level()).isEqualTo("ERROR");
        assertThat(error.thread()).isEqualTo("http-nio-6060-exec-1");
        assertThat(error.logger()).isEqualTo("o.b.s.BookService");
        assertThat(error.detail()).startsWith("java.lang.IllegalStateException: boom").contains("Foo.java:1");
    }

    @Test
    void filtersByLevelAndText() throws IOException {
        assertThat(service.read(null, "WARN", null, 100).entries()).extracting(ServerLogService.LogEntry::level)
                .containsExactly("ERROR", "WARN");
        assertThat(service.read(null, null, "fix_epub_covers", 100).entries()).hasSize(2);
        assertThat(service.read(null, null, "Exile's Return", 100).entries()).singleElement()
                .extracting(ServerLogService.LogEntry::message).asString().contains("Repaired bookId=6814");
        assertThat(service.read(null, null, "Foo.java", 100).entries()).singleElement()
                .extracting(ServerLogService.LogEntry::level).isEqualTo("ERROR");
    }

    @Test
    void keepsTheNewestMatchesWhenOverTheLimit() throws IOException {
        ServerLogService.LogPage page = service.read(null, null, null, 2);

        assertThat(page.entries()).extracting(ServerLogService.LogEntry::message).containsExactly("chatter", "Something broke");
        assertThat(page.matched()).isEqualTo(5);
        assertThat(page.truncated()).isTrue();
    }

    @Test
    void listsAndReadsRotatedArchives() throws IOException {
        Path archive = dir.resolve("trove.log.2026-10-03.0.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            out.write("2026-10-03T09:00:00.000+13:00  INFO [main] o.b.Old : yesterday\n".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(dir.resolve("other.txt"), "not a log");

        List<ServerLogService.LogFile> files = service.files();

        assertThat(files).extracting(ServerLogService.LogFile::name).containsExactly("trove.log", "trove.log.2026-10-03.0.gz");
        assertThat(files.getFirst().current()).isTrue();
        assertThat(service.read("trove.log.2026-10-03.0.gz", null, null, 10).entries()).singleElement()
                .extracting(ServerLogService.LogEntry::message).isEqualTo("yesterday");
    }

    @Test
    void refusesFilesThatAreNotItsLogs() {
        assertThatThrownBy(() -> service.read("../../etc/passwd", null, null, 10)).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> service.read("other.txt", null, null, 10)).isInstanceOf(NoSuchFileException.class);
    }
}
