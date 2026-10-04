package org.booklore.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Finds and removes the advert that OceanofPDF injects into the EPUBs it hands out: a centred
 * "OceanofPDF.com" link at the end of every chapter, plus an empty "oceanofpdf.com" file at the root
 * of the zip.
 * <p>
 * Pages are read as ISO-8859-1, which maps every byte to one char and back, so whatever the book's
 * real encoding, everything outside the removed blocks is written back byte for byte. The pattern is
 * plain ASCII, so it matches the same way in UTF-8 text.
 */
public final class EpubAdCleaner {

    // <div style="..."><p><a href="https://oceanofpdf.com"><i>OceanofPDF.com</i></a></p></div>
    static final Pattern AD_BLOCK = Pattern.compile(
            "<div[^>]*>\\s*<p[^>]*>\\s*<a\\s+href=\"https?://(?:www\\.)?oceanofpdf\\.com/?\"[^>]*>\\s*<i>\\s*OceanofPDF\\.com\\s*</i>\\s*</a>\\s*</p>\\s*</div>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION = Pattern.compile("oceanofpdf", Pattern.CASE_INSENSITIVE);
    private static final String STRAY_FILE = "oceanofpdf.com";
    private static final String MIMETYPE = "mimetype";
    private static final byte[] DEFAULT_MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

    private EpubAdCleaner() {
    }

    /**
     * @param adBlocks       injected link blocks found in the book's pages
     * @param strayFile      whether the empty "oceanofpdf.com" file sits at the root of the zip
     * @param otherMentions  mentions of OceanofPDF in pages, the OPF or the NCX that aren't part of an
     *                       ad block, and so are left alone by {@link #clean}
     */
    public record Findings(int adBlocks, boolean strayFile, int otherMentions) {
        public boolean hasAds() {
            return adBlocks > 0 || strayFile;
        }
    }

    /** Looks for the advert without changing anything. */
    public static Findings scan(Path epub) throws IOException {
        int blocks = 0;
        int others = 0;
        boolean stray = false;
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isStrayFile(entry)) {
                    stray = true;
                } else if (isPage(entry) || isPackageFile(entry)) {
                    String text = read(zip, entry);
                    int found = isPage(entry) ? count(AD_BLOCK, text) : 0;
                    blocks += found;
                    others += count(MENTION, found > 0 ? AD_BLOCK.matcher(text).replaceAll("") : text);
                }
            }
        }
        return new Findings(blocks, stray, others);
    }

    /**
     * Writes a copy of {@code source} to {@code target} without the advert. The mimetype entry goes
     * first and uncompressed, as the EPUB spec requires (some OceanofPDF files have it out of place);
     * every other entry keeps its order and content.
     *
     * @return false, writing nothing, if the book has no advert
     */
    public static boolean clean(Path source, Path target) throws IOException {
        if (!scan(source).hasAds()) {
            return false;
        }
        rewrite(source, target);
        return true;
    }

    /** {@link #clean} for a book already {@linkplain #scan scanned}: writes the copy whether or not it has ads. */
    public static void rewrite(Path source, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile());
             OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {
            ZipEntry mimetypeEntry = zip.getEntry(MIMETYPE);
            byte[] mimetype = mimetypeEntry != null ? readBytes(zip, mimetypeEntry) : DEFAULT_MIMETYPE;
            writeStored(zipOut, MIMETYPE, mimetype);

            Set<String> written = new HashSet<>(Set.of(MIMETYPE));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isStrayFile(entry) || !written.add(entry.getName())) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getLastModifiedTime() != null) {
                    copy.setLastModifiedTime(entry.getLastModifiedTime());
                }
                zipOut.putNextEntry(copy);
                if (isPage(entry)) {
                    String text = read(zip, entry);
                    zipOut.write(AD_BLOCK.matcher(text).replaceAll("").getBytes(StandardCharsets.ISO_8859_1));
                } else if (!entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(zipOut);
                    }
                }
                zipOut.closeEntry();
            }
        }
    }

    private static boolean isStrayFile(ZipEntry entry) {
        return !entry.isDirectory() && entry.getName().equalsIgnoreCase(STRAY_FILE);
    }

    private static boolean isPage(ZipEntry entry) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        return !entry.isDirectory() && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"));
    }

    private static boolean isPackageFile(ZipEntry entry) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        return !entry.isDirectory() && (name.endsWith(".opf") || name.endsWith(".ncx"));
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        return new String(readBytes(zip, entry), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void writeStored(ZipOutputStream zipOut, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zipOut.putNextEntry(entry);
        zipOut.write(data);
        zipOut.closeEntry();
    }
}
