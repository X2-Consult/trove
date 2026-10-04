package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EpubAdCleanerTest {

    // As OceanofPDF injects it, at the end of every chapter.
    private static final String AD = "<div style=\"float: none; margin: 10px 0px 10px 0px; text-align: center;\">"
            + "<p><a href=\"https://oceanofpdf.com\"><i>OceanofPDF.com</i></a></p></div>";
    private static final String CHAPTER = "<html><body>\n<p class=\"c1\">Café at the end — of chapter %d.</p>\n%s</body>\n</html>";

    @TempDir
    Path dir;

    @Test
    void scanCountsAdBlocksAndTheStrayFile() throws IOException {
        Path epub = oceanofPdfEpub(dir.resolve("book.epub"), false);

        EpubAdCleaner.Findings findings = EpubAdCleaner.scan(epub);

        assertThat(findings.adBlocks()).isEqualTo(3);
        assertThat(findings.strayFile()).isTrue();
        assertThat(findings.otherMentions()).isZero();
        assertThat(findings.hasAds()).isTrue();
    }

    @Test
    void cleanRemovesTheAdsAndStrayFile_andLeavesEverythingElseByteForByte() throws IOException {
        Path epub = oceanofPdfEpub(dir.resolve("book.epub"), false);
        Path cleaned = dir.resolve("clean.epub");

        assertThat(EpubAdCleaner.clean(epub, cleaned)).isTrue();

        assertThat(EpubAdCleaner.scan(cleaned)).isEqualTo(new EpubAdCleaner.Findings(0, false, 0));
        assertThat(BookFileIntegrity.check(cleaned)).isNull();
        try (ZipFile zip = new ZipFile(cleaned.toFile())) {
            assertThat(names(zip)).containsExactly("mimetype", "META-INF/container.xml", "OEBPS/content.opf",
                    "OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/ch3.xhtml", "OEBPS/cover.jpg");
            assertThat(text(zip, "OEBPS/ch2.xhtml")).isEqualTo(CHAPTER.formatted(2, ""));
            assertThat(zip.getInputStream(zip.getEntry("OEBPS/cover.jpg")).readAllBytes()).isEqualTo(cover());
        }
    }

    @Test
    void mimetypeIsMovedFirstAndStored() throws IOException {
        Path epub = oceanofPdfEpub(dir.resolve("book.epub"), true);
        Path cleaned = dir.resolve("clean.epub");

        EpubAdCleaner.clean(epub, cleaned);

        try (ZipFile zip = new ZipFile(cleaned.toFile())) {
            ZipEntry first = zip.entries().nextElement();
            assertThat(first.getName()).isEqualTo("mimetype");
            assertThat(first.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(text(zip, "mimetype")).isEqualTo("application/epub+zip");
        }
        // The spec also wants "mimetype" at byte 30, straight after the first local header.
        byte[] head = Files.readAllBytes(cleaned);
        assertThat(new String(head, 30, 28, StandardCharsets.US_ASCII)).isEqualTo("mimetypeapplication/epub+zip");
    }

    @Test
    void bookWithoutAdsIsLeftAlone() throws IOException {
        Path epub = epub(dir.resolve("plain.epub"), List.of("mimetype"), Map.of("OEBPS/ch1.xhtml", CHAPTER.formatted(1, "")));
        Path cleaned = dir.resolve("clean.epub");

        assertThat(EpubAdCleaner.scan(epub).hasAds()).isFalse();
        assertThat(EpubAdCleaner.clean(epub, cleaned)).isFalse();
        assertThat(cleaned).doesNotExist();
    }

    @Test
    void otherMentionsAreCountedButKept() throws IOException {
        String credit = "<p>Downloaded from OceanofPDF</p>";
        Path epub = epub(dir.resolve("book.epub"), List.of("mimetype"),
                Map.of("OEBPS/ch1.xhtml", CHAPTER.formatted(1, credit + AD)));
        Path cleaned = dir.resolve("clean.epub");

        assertThat(EpubAdCleaner.scan(epub)).isEqualTo(new EpubAdCleaner.Findings(1, false, 1));
        EpubAdCleaner.clean(epub, cleaned);

        try (ZipFile zip = new ZipFile(cleaned.toFile())) {
            assertThat(text(zip, "OEBPS/ch1.xhtml")).isEqualTo(CHAPTER.formatted(1, credit));
        }
    }

    @Test
    void adWithWhitespaceBetweenTagsStillMatches() throws IOException {
        String spaced = "<div class=\"x\">\r\n  <p>\r\n    <a href=\"http://www.oceanofpdf.com/\"><i>OceanofPDF.com</i></a>\r\n  </p>\r\n</div>";
        Path epub = epub(dir.resolve("book.epub"), List.of("mimetype"), Map.of("OEBPS/ch1.html", CHAPTER.formatted(1, spaced)));

        assertThat(EpubAdCleaner.scan(epub).adBlocks()).isEqualTo(1);
    }

    /** Laid out like the OceanofPDF files in the dev library, optionally with mimetype out of place. */
    private Path oceanofPdfEpub(Path path, boolean mimetypeLate) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", "<container/>");
        entries.put("OEBPS/content.opf", "<package/>");
        for (int i = 1; i <= 3; i++) {
            entries.put("OEBPS/ch" + i + ".xhtml", CHAPTER.formatted(i, AD));
        }
        List<String> order = new ArrayList<>(List.of("mimetype", "oceanofpdf.com"));
        if (mimetypeLate) {
            Collections.reverse(order);
        }
        return epub(path, order, entries);
    }

    private Path epub(Path path, List<String> leading, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : leading) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write((name.equals("mimetype") ? "application/epub+zip" : "").getBytes(StandardCharsets.US_ASCII));
                zip.closeEntry();
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (leading.contains("oceanofpdf.com")) {
                zip.putNextEntry(new ZipEntry("OEBPS/cover.jpg"));
                zip.write(cover());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static byte[] cover() {
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static List<String> names(ZipFile zip) {
        return zip.stream().map(ZipEntry::getName).toList();
    }

    private static String text(ZipFile zip, String name) throws IOException {
        return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
