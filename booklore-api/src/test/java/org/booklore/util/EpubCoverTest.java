package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.imageio.ImageIO;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpubCoverTest {

    @TempDir
    Path dir;

    @Test
    void checkPassesAGoodCover() throws Exception {
        Path epub = epub("good.epub", "2.0",
                "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"images/cover.jpg\" media-type=\"image/jpeg\"/>",
                Map.of("OEBPS/images/cover.jpg", image("jpeg", 300, 450)));

        EpubCover.Check check = EpubCover.check(epub);

        assertThat(check.ok()).isTrue();
        assertThat(check.entry()).isEqualTo("OEBPS/images/cover.jpg");
        assertThat(check.width()).isEqualTo(300);
        assertThat(check.height()).isEqualTo(450);
    }

    @Test
    void checkFindsTheUsualProblems() throws Exception {
        assertThat(EpubCover.check(epub("none.epub", "2.0", "", "", Map.of())).problem())
                .isEqualTo(EpubCover.Problem.NO_COVER);
        assertThat(EpubCover.check(epub("missing.epub", "2.0", "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>", Map.of())).problem())
                .isEqualTo(EpubCover.Problem.MISSING_FILE);
        assertThat(EpubCover.check(epub("wrong.epub", "2.0", "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"cover.png\" media-type=\"image/png\"/>",
                Map.of("OEBPS/cover.png", image("jpeg", 10, 10)))).problem())
                .isEqualTo(EpubCover.Problem.WRONG_TYPE);
        assertThat(EpubCover.check(epub("page.epub", "2.0", "<meta name=\"cover\" content=\"cover\"/>",
                "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>",
                Map.of("OEBPS/cover.xhtml", "<html/>".getBytes(StandardCharsets.UTF_8)))).problem())
                .isEqualTo(EpubCover.Problem.NOT_AN_IMAGE);
        assertThat(EpubCover.check(epub("junk.epub", "2.0", "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>",
                Map.of("OEBPS/cover.jpg", "<html/>".getBytes(StandardCharsets.UTF_8)))).problem())
                .isEqualTo(EpubCover.Problem.UNREADABLE);
    }

    @Test
    void coverImagePropertyIsPreferredOverACoverPageWithTheCoverId() throws Exception {
        Path epub = epub("epub3.epub", "3.0", "",
                "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"ci\" href=\"cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>",
                Map.of("OEBPS/cover.png", image("png", 20, 30), "OEBPS/cover.xhtml", "<html/>".getBytes(StandardCharsets.UTF_8)));

        assertThat(EpubCover.check(epub).entry()).isEqualTo("OEBPS/cover.png");
    }

    @Test
    void applyReencodesToTheDeclaredType() throws Exception {
        Path epub = epub("png.epub", "2.0", "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"cover.png\" media-type=\"image/png\"/>",
                Map.of("OEBPS/cover.png", image("png", 10, 10)));

        EpubCover.Written written = applyAndRezip(epub, image("jpeg", 600, 900));

        assertThat(written.entry()).isEqualTo("OEBPS/cover.png");
        assertThat(EpubCover.sniff(written.bytes())).isEqualTo("image/png");
        assertThat(EpubCover.confirm(epub, written)).isNull();
        assertThat(EpubCover.check(epub).width()).isEqualTo(600);
    }

    @Test
    void applyKeepsAnImageThatAlreadyHasTheRightTypeByteForByte() throws Exception {
        Path epub = epub("jpg.epub", "2.0", "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>",
                Map.of("OEBPS/cover.jpg", image("jpeg", 10, 10)));
        byte[] newCover = image("jpeg", 400, 600);

        EpubCover.Written written = applyAndRezip(epub, newCover);

        assertThat(written.bytes()).isEqualTo(newCover);
        assertThat(EpubCover.confirm(epub, written)).isNull();
    }

    @Test
    void applyAddsACoverToABookWithout() throws Exception {
        Path epub2 = epub("none2.epub", "2.0", "", "", Map.of());
        Path epub3 = epub("none3.epub", "3.0", "", "", Map.of());

        EpubCover.Written written2 = applyAndRezip(epub2, image("png", 300, 450));
        EpubCover.Written written3 = applyAndRezip(epub3, image("jpeg", 300, 450));

        assertThat(written2.entry()).isEqualTo("OEBPS/trove-cover.jpg");
        assertThat(EpubCover.confirm(epub2, written2)).isNull();
        assertThat(EpubCover.confirm(epub3, written3)).isNull();
        String opf3 = entry(epub3, "OEBPS/content.opf");
        assertThat(opf3).contains("properties=\"cover-image\"").contains("name=\"cover\"").contains("content=\"trove-cover\"");
        assertThat(entry(epub2, "OEBPS/content.opf")).doesNotContain("properties=");
    }

    @Test
    void applyRepointsAMetaThatNamesTheCoverPage() throws Exception {
        Path epub = epub("page.epub", "2.0", "<meta name=\"cover\" content=\"cover\"/>",
                "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>",
                Map.of("OEBPS/cover.xhtml", "<html/>".getBytes(StandardCharsets.UTF_8)));

        EpubCover.Written written = applyAndRezip(epub, image("jpeg", 300, 450));

        assertThat(EpubCover.confirm(epub, written)).isNull();
        assertThat(entry(epub, "OEBPS/cover.xhtml")).isEqualTo("<html/>");
    }

    @Test
    void applyReplacesAGifCoverWithANewJpeg() throws Exception {
        Path epub = epub("gif.epub", "3.0", "",
                "<item id=\"img\" href=\"cover.gif\" media-type=\"image/gif\" properties=\"cover-image\"/>",
                Map.of("OEBPS/cover.gif", image("gif", 10, 10)));

        EpubCover.Written written = applyAndRezip(epub, image("png", 300, 450));

        assertThat(written.entry()).isEqualTo("OEBPS/trove-cover.jpg");
        assertThat(EpubCover.sniff(written.bytes())).isEqualTo("image/jpeg");
        assertThat(EpubCover.confirm(epub, written)).isNull();
    }

    @Test
    void applyRefusesSomethingThatIsNotAnImage() throws Exception {
        Path epub = epub("x.epub", "2.0", "", "", Map.of());
        Path extracted = extract(epub);
        Path opfFile = extracted.resolve("OEBPS/content.opf");
        Document opf = SecureXmlUtils.createSecureDocumentBuilder(true).parse(opfFile.toFile());

        assertThatThrownBy(() -> EpubCover.apply(extracted, opfFile, opf, "not an image".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void confirmNoticesWhenTheFileDoesNotHaveTheWrittenCover() throws Exception {
        Path epub = epub("c.epub", "2.0", "<meta name=\"cover\" content=\"img\"/>",
                "<item id=\"img\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>",
                Map.of("OEBPS/cover.jpg", image("jpeg", 10, 10)));

        String problem = EpubCover.confirm(epub, new EpubCover.Written("OEBPS/cover.jpg", image("jpeg", 20, 20), 20, 20));

        assertThat(problem).contains("isn't the one that was written");
    }

    /** Writes the cover the way EpubMetadataWriter does: extract, apply, save the OPF, zip. */
    private EpubCover.Written applyAndRezip(Path epub, byte[] cover) throws Exception {
        Path extracted = extract(epub);
        Path opfFile = extracted.resolve("OEBPS/content.opf");
        Document opf = SecureXmlUtils.createSecureDocumentBuilder(true).parse(opfFile.toFile());
        EpubCover.Written written = EpubCover.apply(extracted, opfFile, opf, cover);
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(opf), new StreamResult(opfFile.toFile()));
        try (OutputStream out = Files.newOutputStream(epub); ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(extracted)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(extracted.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return written;
    }

    private Path extract(Path epub) throws IOException {
        Path out = Files.createTempDirectory(dir, "x");
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                Path target = out.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.write(target, zip.getInputStream(entry).readAllBytes());
            }
        }
        return out;
    }

    private Path epub(String name, String version, String meta, String items, Map<String, byte[]> files) throws IOException {
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="%s" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title>%s</metadata>
                  <manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>%s</manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>""".formatted(version, meta, items);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        entries.put("META-INF/container.xml", ("<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/ch1.xhtml", "<html/>".getBytes(StandardCharsets.UTF_8));
        entries.putAll(files);
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static String entry(Path epub, String name) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            image.setRGB(x, height / 2, 0x3366CC);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
