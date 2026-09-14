package org.booklore.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Works out a page count for EPUB and FB2 books, for when neither the file's own metadata nor any
 * metadata provider has one. In order of preference:
 * <ol>
 *   <li>the print edition's page numbers, which many EPUBs carry as a page list (EPUB 3 nav
 *       {@code page-list}, or an EPUB 2 NCX {@code pageList}): the last numbered page;</li>
 *   <li>a fixed-layout EPUB (comics, picture books): one page per spine item;</li>
 *   <li>otherwise an estimate from the word count, at a words-per-page figure the caller measures
 *       from books whose page count is known ({@value #WORDS_PER_PAGE} when there's nothing to
 *       measure).</li>
 * </ol>
 */
public final class PageCounter {

    /**
     * Used only when there are too few books with a page count to measure. Modern trade paperbacks
     * fit about 250-300 words to a page and dense classics 330-370; an 80,000-word novel comes out
     * at 267 pages.
     */
    public static final int WORDS_PER_PAGE = 300;

    /** Chinese and Japanese are written without spaces; two characters count as about one word. */
    private static final int CJK_CHARACTERS_PER_WORD = 2;

    /** Stops a malformed or hostile file from being read without end. */
    private static final long MAX_TEXT_BYTES = 64L * 1024 * 1024;

    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*");
    private static final Pattern PAGE_NUMBER = Pattern.compile("\\d{1,5}");

    public enum Source {
        /** A PDF's own pages. */
        PDF_PAGES,
        /** The page images in a comic archive. */
        COMIC_PAGES,
        /** The print edition's page numbers, embedded in the EPUB. */
        PRINT_PAGE_NUMBERS,
        /** A fixed-layout EPUB, one page per spine item. */
        FIXED_LAYOUT,
        /** Estimated from the word count. */
        WORD_COUNT
    }

    public record Result(int pages, Source source) {}

    private PageCounter() {
    }

    public static Optional<Result> countEpub(Path epub, int wordsPerPage) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                return Optional.empty();
            }
            Document opf = readXml(zip, opfPath);
            if (opf == null) {
                return Optional.empty();
            }
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

            Map<String, Element> manifest = new HashMap<>();
            for (Element item : opf.select("*|manifest > *|item")) {
                manifest.put(item.attr("id"), item);
            }
            List<String> spine = new ArrayList<>();
            for (Element itemref : opf.select("*|spine > *|itemref")) {
                Element item = manifest.get(itemref.attr("idref"));
                if (item != null) {
                    spine.add(resolve(opfDir, item.attr("href")));
                }
            }

            Optional<Integer> printed = printPageCount(zip, opf, opfDir, manifest);
            if (printed.isPresent()) {
                return Optional.of(new Result(printed.get(), Source.PRINT_PAGE_NUMBERS));
            }

            boolean fixedLayout = opf.select("*|metadata > *|meta").stream()
                    .anyMatch(meta -> "rendition:layout".equals(meta.attr("property"))
                            && "pre-paginated".equals(meta.text().trim()));
            if (fixedLayout && !spine.isEmpty()) {
                return Optional.of(new Result(spine.size(), Source.FIXED_LAYOUT));
            }

            return fromWords(spineWords(zip, spine), wordsPerPage);
        }
    }

    /** The words in an EPUB's text, for measuring words per page against a known page count. */
    public static long epubWords(Path epub) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            String opfPath = findOpfPath(zip);
            Document opf = opfPath == null ? null : readXml(zip, opfPath);
            if (opf == null) {
                return 0;
            }
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Map<String, Element> manifest = new HashMap<>();
            opf.select("*|manifest > *|item").forEach(item -> manifest.put(item.attr("id"), item));
            List<String> spine = new ArrayList<>();
            for (Element itemref : opf.select("*|spine > *|itemref")) {
                Element item = manifest.get(itemref.attr("idref"));
                if (item != null) {
                    spine.add(resolve(opfDir, item.attr("href")));
                }
            }
            return spineWords(zip, spine);
        }
    }

    private static long spineWords(ZipFile zip, List<String> spine) throws IOException {
        long words = 0;
        long budget = MAX_TEXT_BYTES;
        for (String href : spine) {
            ZipEntry entry = zip.getEntry(href);
            if (entry == null || budget <= 0) {
                continue;
            }
            byte[] bytes = readLimited(zip, entry, budget);
            budget -= bytes.length;
            words += countWords(bodyText(Jsoup.parse(new String(bytes, StandardCharsets.UTF_8))));
        }
        return words;
    }

    public static Optional<Result> countFb2(Path fb2, int wordsPerPage) throws IOException {
        return fromWords(fb2Words(fb2), wordsPerPage);
    }

    /** The words in an FB2 book's text. */
    public static long fb2Words(Path fb2) throws IOException {
        try (InputStream raw = Files.newInputStream(fb2);
             InputStream in = fb2.getFileName().toString().toLowerCase().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = in.readNBytes((int) Math.min(MAX_TEXT_BYTES, Integer.MAX_VALUE - 8));
            Document doc = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), "", Parser.xmlParser());
            long words = 0;
            for (Element body : doc.select("*|body")) {
                words += countWords(body.text());
            }
            return words;
        }
    }

    private static String bodyText(Document doc) {
        return doc.body() != null ? doc.body().text() : doc.text();
    }

    static long countWords(String text) {
        Matcher cjk = CJK.matcher(text);
        long cjkCharacters = 0;
        while (cjk.find()) {
            cjkCharacters++;
        }
        Matcher word = WORD.matcher(cjkCharacters > 0 ? CJK.matcher(text).replaceAll(" ") : text);
        long words = 0;
        while (word.find()) {
            words++;
        }
        return words + cjkCharacters / CJK_CHARACTERS_PER_WORD;
    }

    private static Optional<Result> fromWords(long words, int wordsPerPage) {
        if (words <= 0 || wordsPerPage <= 0) {
            return Optional.empty();
        }
        int pages = (int) Math.max(1, Math.round((double) words / wordsPerPage));
        return Optional.of(new Result(pages, Source.WORD_COUNT));
    }

    /**
     * The last numbered page in the print page list. Front matter numbered in roman numerals is left
     * out, as catalogues do. Falls back to the number of entries when none is numeric.
     */
    private static Optional<Integer> printPageCount(ZipFile zip, Document opf, String opfDir, Map<String, Element> manifest) throws IOException {
        List<String> labels = new ArrayList<>();

        for (Element item : manifest.values()) {
            if (!(" " + item.attr("properties") + " ").contains(" nav ")) {
                continue;
            }
            Document nav = readXml(zip, resolve(opfDir, item.attr("href")));
            if (nav == null) {
                continue;
            }
            for (Element list : nav.select("*|nav")) {
                if ((" " + list.attr("epub:type") + " ").contains(" page-list ")) {
                    list.select("*|a").forEach(a -> labels.add(a.text().trim()));
                }
            }
        }

        if (labels.isEmpty()) {
            Element tocItem = manifest.get(opf.select("*|spine").attr("toc"));
            if (tocItem != null) {
                Document ncx = readXml(zip, resolve(opfDir, tocItem.attr("href")));
                if (ncx != null) {
                    ncx.select("*|pageList *|pageTarget").forEach(target -> labels.add(target.select("*|navLabel *|text").text().trim()));
                }
            }
        }

        // A handful of entries is a few landmarks rather than a real page list.
        if (labels.size() < 10) {
            return Optional.empty();
        }
        int lastNumbered = labels.stream()
                .filter(label -> PAGE_NUMBER.matcher(label).matches())
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        // A number far beyond the entry count means the labels aren't page numbers.
        if (lastNumbered > 0 && lastNumbered <= labels.size() * 3L) {
            return Optional.of(lastNumbered);
        }
        return Optional.of(labels.size());
    }

    private static String findOpfPath(ZipFile zip) throws IOException {
        Document container = readXml(zip, "META-INF/container.xml");
        if (container != null) {
            String path = container.select("*|rootfile").attr("full-path");
            if (!path.isBlank()) {
                return path;
            }
        }
        return zip.stream().map(ZipEntry::getName).filter(name -> name.toLowerCase().endsWith(".opf")).findFirst().orElse(null);
    }

    private static Document readXml(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        return Jsoup.parse(new String(readLimited(zip, entry, MAX_TEXT_BYTES), StandardCharsets.UTF_8), "", Parser.xmlParser());
    }

    private static byte[] readLimited(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8));
        }
    }

    /** A manifest href is relative to the OPF's folder and may be percent-encoded. */
    private static String resolve(String baseDir, String href) {
        String path = URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        List<String> parts = new ArrayList<>();
        for (String part : (baseDir + path).split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }
}
