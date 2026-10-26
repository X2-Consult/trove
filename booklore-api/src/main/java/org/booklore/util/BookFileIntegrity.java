package org.booklore.util;

import com.github.junrar.Archive;
import com.github.junrar.exception.UnsupportedRarVersionException;
import com.github.junrar.rarfile.FileHeader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.jaudiotagger.audio.AudioFileIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks that a book file is structurally sound for its format, deep enough to catch a file that
 * was cut short or damaged in the middle, not just one with a bad header. Used by the integrity
 * check task and before a rewritten file replaces a book in the library ({@link SafeFiles}).
 * <p>
 * Formats without a check (MOBI, AZW3, Opus, images, plain text) pass as long as they're not empty.
 */
@Slf4j
public final class BookFileIntegrity {

    private static final int BUFFER_SIZE = 64 * 1024;
    // PDFs end with "%%EOF", though writers may add a little trailing whitespace or junk after it.
    private static final int PDF_EOF_WINDOW = 8 * 1024;

    private BookFileIntegrity() {
    }

    /** True if {@link #check} does more for this file than confirm it isn't empty. */
    public static boolean isCheckable(Path file) {
        return switch (extension(file)) {
            case "epub", "kepub", "cbz", "zip", "pdf", "cb7", "7z", "cbr", "rar", "m4b", "m4a", "mp4", "mp3", "fb2", "json" -> true;
            default -> false;
        };
    }

    /**
     * @return null if the file looks intact, otherwise a short description of the problem.
     */
    public static String check(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "file missing";
            }
            if (Files.size(file) == 0) {
                return "file is empty";
            }
            return switch (extension(file)) {
                case "epub", "kepub" -> checkZip(file);
                // Comic archives are often misnamed (a zip called .cbr), so go by the content.
                case "cbz", "cbr", "cb7", "zip", "rar", "7z" -> switch (ArchiveUtils.detectArchiveType(file.toFile())) {
                    case ZIP -> checkZip(file);
                    case RAR -> checkRar(file);
                    case SEVEN_ZIP -> check7z(file);
                    default -> "not a zip, RAR or 7z archive";
                };
                case "pdf" -> checkPdf(file);
                case "m4b", "m4a", "mp4" -> checkMp4(file);
                case "mp3" -> checkMp3(file);
                case "fb2" -> checkXml(file);
                case "json" -> checkJson(file);
                default -> null;
            };
        } catch (Exception e) {
            return describe(e);
        }
    }

    // Reads every entry and checks its CRC-32 against the archive's central directory, the same depth
    // as `unzip -t`. java.util.zip.ZipFile doesn't compare CRCs itself (only ZipInputStream does), so
    // damage inside uncompressed data - images, already-compressed content - would otherwise pass.
    private static String checkZip(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            var entries = zip.entries();
            if (!entries.hasMoreElements()) {
                return "archive has no entries";
            }
            CRC32 crc = new CRC32();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                crc.reset();
                long read = 0;
                try (InputStream in = zip.getInputStream(entry)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        crc.update(buffer, 0, n);
                        read += n;
                    }
                }
                if (entry.getSize() >= 0 && read != entry.getSize()) {
                    return "entry " + entry.getName() + " is " + read + " bytes, expected " + entry.getSize();
                }
                if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
                    return "entry " + entry.getName() + " fails its checksum";
                }
            }
            return null;
        }
    }

    // A truncated PDF loses its trailer, and PDFBox's lenient parser can still open it, so the
    // end marker is checked separately.
    private static String checkPdf(Path file) throws IOException {
        if (!endsWithPdfEof(file)) {
            return "no %%EOF marker at the end (file cut short?)";
        }
        try (PDDocument pdf = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            return pdf.getNumberOfPages() > 0 ? null : "PDF has no pages";
        } catch (InvalidPasswordException e) {
            return null; // password-protected: the structure parsed, the contents can't be checked
        }
    }

    private static boolean endsWithPdfEof(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int window = (int) Math.min(PDF_EOF_WINDOW, length);
            byte[] tail = new byte[window];
            raf.seek(length - window);
            raf.readFully(tail);
            return new String(tail, StandardCharsets.ISO_8859_1).contains("%%EOF");
        }
    }

    // commons-compress verifies each entry's CRC as it's read.
    private static String check7z(Path file) throws IOException {
        try (SevenZFile archive = SevenZFile.builder().setFile(file.toFile()).get()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            SevenZArchiveEntry entry;
            boolean any = false;
            while ((entry = archive.getNextEntry()) != null) {
                any = true;
                if (entry.isDirectory() || !entry.hasStream()) {
                    continue;
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    while (in.read(buffer) != -1) {
                        // consume to validate the CRC
                    }
                }
            }
            return any ? null : "archive has no entries";
        }
    }

    private static String checkRar(Path file) throws Exception {
        try (Archive archive = new Archive(file.toFile())) {
            if (archive.getFileHeaders().isEmpty()) {
                return "archive has no entries";
            }
            for (FileHeader header : archive.getFileHeaders()) {
                if (!header.isDirectory()) {
                    archive.extractFile(header, OutputStream.nullOutputStream()); // junrar checks the CRC
                }
            }
            return null;
        } catch (UnsupportedRarVersionException e) {
            if (!UnrarHelper.isAvailable()) {
                return null; // a RAR version junrar can't read, and no unrar tool: can't check here
            }
            return UnrarHelper.testArchive(file) ? null : "unrar reports the archive is damaged";
        }
    }

    // An MP4/M4B file is a sequence of top-level boxes, each starting with its own length. Walking
    // them catches a file cut short anywhere, including inside the (large) audio data box.
    private static String checkMp4(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long pos = 0;
            boolean hasMoov = false;
            while (pos < length) {
                if (length - pos < 8) {
                    return "truncated box header at byte " + pos;
                }
                raf.seek(pos);
                long size = Integer.toUnsignedLong(raf.readInt());
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                if (size == 1) {
                    if (length - pos < 16) {
                        return "truncated '" + type + "' box header at byte " + pos;
                    }
                    size = raf.readLong();
                } else if (size == 0) {
                    size = length - pos; // box runs to the end of the file
                }
                if (size < 8) {
                    return "invalid '" + type + "' box size " + size + " at byte " + pos;
                }
                if (pos + size > length) {
                    return "'" + type + "' box needs " + (pos + size) + " bytes, file has " + length + " (cut short?)";
                }
                if ("moov".equals(type)) {
                    hasMoov = true;
                }
                pos += size;
            }
            return hasMoov ? null : "no 'moov' box (no track information)";
        }
    }

    private static String checkMp3(Path file) throws Exception {
        var audio = AudioFileIO.read(file.toFile());
        return audio.getAudioHeader() != null && audio.getAudioHeader().getTrackLength() > 0 ? null : "no audio frames";
    }

    private static String checkXml(Path file) throws Exception {
        SecureXmlUtils.createSecureDocumentBuilder(true).parse(file.toFile());
        return null;
    }

    private static String checkJson(Path file) throws IOException {
        new tools.jackson.databind.json.JsonMapper().readTree(file.toFile());
        return null;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }
}
