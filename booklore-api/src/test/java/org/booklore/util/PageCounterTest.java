package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PageCounterTest {

    @TempDir
    Path dir;

    private static final String CONTAINER = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""";

    private Path epub(String name, Map<String, String> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("mimetype", "application/epub+zip");
            all.put("META-INF/container.xml", CONTAINER);
            all.putAll(entries);
            for (Map.Entry<String, String> entry : all.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static String chapter(int words) {
        String text = IntStream.range(0, words).mapToObj(i -> "word").collect(Collectors.joining(" "));
        return "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter title words</title></head><body><p>"
                + text + "</p></body></html>";
    }

    private static String opf(String metadataExtra, String manifestExtra, String spineAttrs, int chapters) {
        String manifest = IntStream.range(0, chapters)
                .mapToObj(i -> "<item id=\"c" + i + "\" href=\"text/ch" + i + ".xhtml\" media-type=\"application/xhtml+xml\"/>")
                .collect(Collectors.joining());
        String spine = IntStream.range(0, chapters).mapToObj(i -> "<itemref idref=\"c" + i + "\"/>").collect(Collectors.joining());
        return "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata>" + metadataExtra
                + "</metadata><manifest>" + manifest + manifestExtra + "</manifest><spine" + spineAttrs + ">" + spine + "</spine></package>";
    }

    private static Map<String, String> chapters(int count, int wordsEach) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put("OEBPS/text/ch" + i + ".xhtml", chapter(wordsEach));
        }
        return entries;
    }

    @Test
    void estimatesFromTheWordCountAtTheGivenWordsPerPage() throws IOException {
        Map<String, String> entries = chapters(4, 1250);
        entries.put("OEBPS/content.opf", opf("", "", "", 4));
        Path book = epub("plain.epub", entries);

        assertThat(PageCounter.epubWords(book)).isEqualTo(5000);
        assertThat(PageCounter.countEpub(book, 250)).contains(new PageCounter.Result(20, PageCounter.Source.WORD_COUNT));
        assertThat(PageCounter.countEpub(book, 200)).contains(new PageCounter.Result(25, PageCounter.Source.WORD_COUNT));
    }

    @Test
    void usesTheLastPrintedPageNumberFromAnEpub3PageList() throws IOException {
        String roman = IntStream.rangeClosed(1, 12).mapToObj(i -> "<li><a href=\"text/ch0.xhtml#r" + i + "\">" + "ivx".charAt(i % 3) + "</a></li>").collect(Collectors.joining());
        String pages = IntStream.rangeClosed(1, 212).mapToObj(i -> "<li><a href=\"text/ch0.xhtml#p" + i + "\">" + i + "</a></li>").collect(Collectors.joining());
        String nav = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>"
                + "<nav epub:type=\"toc\"><ol><li><a href=\"text/ch0.xhtml\">One</a></li></ol></nav>"
                + "<nav epub:type=\"page-list\" hidden=\"\"><ol>" + roman + pages + "</ol></nav></body></html>";
        Map<String, String> entries = chapters(1, 500);
        entries.put("OEBPS/nav.xhtml", nav);
        entries.put("OEBPS/content.opf", opf("", "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>", "", 1));

        assertThat(PageCounter.countEpub(epub("printed.epub", entries), 250))
                .contains(new PageCounter.Result(212, PageCounter.Source.PRINT_PAGE_NUMBERS));
    }

    @Test
    void readsAnEpub2NcxPageList() throws IOException {
        String targets = IntStream.rangeClosed(1, 40)
                .mapToObj(i -> "<pageTarget id=\"p" + i + "\" type=\"normal\" value=\"" + i + "\"><navLabel><text>" + i + "</text></navLabel><content src=\"text/ch0.xhtml#p" + i + "\"/></pageTarget>")
                .collect(Collectors.joining());
        String ncx = "<?xml version=\"1.0\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><navMap/><pageList>" + targets + "</pageList></ncx>";
        Map<String, String> entries = chapters(1, 500);
        entries.put("OEBPS/toc.ncx", ncx);
        entries.put("OEBPS/content.opf", opf("", "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>", " toc=\"ncx\"", 1));

        assertThat(PageCounter.countEpub(epub("ncx.epub", entries), 250))
                .contains(new PageCounter.Result(40, PageCounter.Source.PRINT_PAGE_NUMBERS));
    }

    @Test
    void aFewLandmarksAreNotTreatedAsAPageList() throws IOException {
        String nav = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>"
                + "<nav epub:type=\"page-list\"><ol><li><a href=\"text/ch0.xhtml#p1\">1</a></li><li><a href=\"text/ch0.xhtml#p9\">900</a></li></ol></nav></body></html>";
        Map<String, String> entries = chapters(1, 2500);
        entries.put("OEBPS/nav.xhtml", nav);
        entries.put("OEBPS/content.opf", opf("", "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>", "", 1));

        assertThat(PageCounter.countEpub(epub("landmarks.epub", entries), 250))
                .contains(new PageCounter.Result(10, PageCounter.Source.WORD_COUNT));
    }

    @Test
    void countsAFixedLayoutEpubOnePagePerSpineItem() throws IOException {
        Map<String, String> entries = chapters(24, 3);
        entries.put("OEBPS/content.opf", opf("<meta property=\"rendition:layout\">pre-paginated</meta>", "", "", 24));

        assertThat(PageCounter.countEpub(epub("fixed.epub", entries), 250))
                .contains(new PageCounter.Result(24, PageCounter.Source.FIXED_LAYOUT));
    }

    @Test
    void handlesPrefixedOpfElementsAndEncodedHrefs() throws IOException {
        String opf = "<?xml version=\"1.0\"?><opf:package xmlns:opf=\"http://www.idpf.org/2007/opf\" version=\"2.0\"><opf:metadata/>"
                + "<opf:manifest><opf:item id=\"a\" href=\"text/Chapter%20One.xhtml\" media-type=\"application/xhtml+xml\"/></opf:manifest>"
                + "<opf:spine><opf:itemref idref=\"a\"/></opf:spine></opf:package>";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OEBPS/text/Chapter One.xhtml", chapter(750));
        entries.put("OEBPS/content.opf", opf);

        assertThat(PageCounter.countEpub(epub("prefixed.epub", entries), 250))
                .contains(new PageCounter.Result(3, PageCounter.Source.WORD_COUNT));
    }

    @Test
    void anEpubWithNoTextGivesNothing() throws IOException {
        Map<String, String> entries = chapters(2, 0);
        entries.put("OEBPS/content.opf", opf("", "", "", 2));

        assertThat(PageCounter.countEpub(epub("empty.epub", entries), 250)).isEmpty();
    }

    @Test
    void countsTheWordsInAnFb2Body() throws IOException {
        String words = IntStream.range(0, 1000).mapToObj(i -> "слово").collect(Collectors.joining(" "));
        Path fb2 = dir.resolve("book.fb2");
        Files.writeString(fb2, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><book-title>Title</book-title></title-info></description>"
                + "<body><section><p>" + words + "</p></section></body><binary id=\"cover\">AAAA</binary></FictionBook>");

        assertThat(PageCounter.fb2Words(fb2)).isEqualTo(1000);
        assertThat(PageCounter.countFb2(fb2, 250)).contains(new PageCounter.Result(4, PageCounter.Source.WORD_COUNT));
    }

    @Test
    void countsWordsAcrossScripts() {
        assertThat(PageCounter.countWords("It's a well-known fact, isn't it? 42 of them.")).isEqualTo(9);
        assertThat(PageCounter.countWords("Ça va très bien")).isEqualTo(4);
        // Chinese has no spaces: two characters make about one word.
        assertThat(PageCounter.countWords("我们今天去公园散步")).isEqualTo(4);
    }
}
