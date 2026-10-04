package org.booklore.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds, checks and writes the cover image of an EPUB.
 * <p>
 * Readers look for the cover through the OPF: {@code <meta name="cover" content="item-id"/>} (EPUB 2,
 * also what Kobo uses) or a manifest item with {@code properties="cover-image"} (EPUB 3). A cover only
 * shows if that item is an image file that's really there and really of its declared type: a WebP or
 * PNG saved under a JPEG declaration shows as no cover on many readers.
 * <p>
 * {@link #apply} writes a new cover so that it passes {@link #check}: the image is re-encoded to the
 * declared type when it differs, and a book without a usable cover item gets one, plus the meta entry.
 */
public final class EpubCover {

    private static final String OPF_NS = "http://www.idpf.org/2007/opf";
    private static final Set<String> FALLBACK_IDS = Set.of("cover-image", "cover", "coverimg");
    private static final String NEW_COVER_NAME = "trove-cover";
    private static final float JPEG_QUALITY = 0.92f;

    private EpubCover() {
    }

    public enum Problem {
        NO_COVER("no cover is declared"),
        NOT_AN_IMAGE("the declared cover isn't an image"),
        MISSING_FILE("the declared cover image isn't in the file"),
        UNREADABLE("the cover image can't be decoded"),
        WRONG_TYPE("the cover image isn't the type it's declared as");

        private final String description;

        Problem(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /**
     * @param problem null if the cover is fine
     * @param entry   the cover image's path in the zip, when there is one
     */
    public record Check(Problem problem, String detail, String entry, int width, int height) {
        public boolean ok() {
            return problem == null;
        }

        public String describe() {
            if (ok()) {
                return "cover OK (" + width + "x" + height + ")";
            }
            return problem.description() + (detail != null ? " (" + detail + ")" : "");
        }

        static Check problem(Problem problem, String detail, String entry) {
            return new Check(problem, detail, entry, 0, 0);
        }
    }

    /** What {@link #apply} wrote: the image's path in the zip and its exact bytes. */
    public record Written(String entry, byte[] bytes, int width, int height) {
    }

    /** Looks at the cover of an EPUB on disk without changing it. */
    public static Check check(Path epub) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            String opfPath = opfPath(zip);
            Document opf;
            try (InputStream in = zip.getInputStream(requireEntry(zip, opfPath))) {
                opf = SecureXmlUtils.createSecureDocumentBuilder(true).parse(in);
            }
            Element item = findImageCoverItem(opf);
            if (item == null) {
                Element declared = findDeclaredCoverItem(opf);
                return declared != null
                        ? Check.problem(Problem.NOT_AN_IMAGE, declared.getAttribute("media-type"), resolve(opfPath, declared))
                        : Check.problem(Problem.NO_COVER, null, null);
            }
            String entryName = resolve(opfPath, item);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return Check.problem(Problem.MISSING_FILE, entryName, entryName);
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            return checkImage(bytes, item.getAttribute("media-type"), entryName);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Couldn't read the EPUB's package file: " + e.getMessage(), e);
        }
    }

    /**
     * Confirms that {@code epub} now has exactly the cover {@link #apply} wrote.
     *
     * @return null if it does, otherwise what's wrong
     */
    public static String confirm(Path epub, Written written) throws IOException {
        Check check = check(epub);
        if (!check.ok()) {
            return check.describe();
        }
        if (!written.entry().equals(check.entry())) {
            return "the book's cover is " + check.entry() + ", not the image written to " + written.entry();
        }
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            try (InputStream in = zip.getInputStream(zip.getEntry(written.entry()))) {
                if (!Arrays.equals(in.readAllBytes(), written.bytes())) {
                    return "the cover image in the file isn't the one that was written";
                }
            }
        }
        return null;
    }

    /**
     * Writes {@code image} as the cover of an extracted EPUB whose package document is {@code opf}
     * (at {@code opfFile}), adding a cover item and meta entry when the book has no usable one. The
     * caller saves {@code opf} and zips the folder.
     */
    public static Written apply(Path extractedDir, Path opfFile, Document opf, byte[] image) throws IOException {
        BufferedImage decoded = decode(image);
        if (decoded == null) {
            throw new IOException("the new cover isn't an image Trove can read");
        }
        Element manifest = first(opf.getDocumentElement(), "manifest");
        if (manifest == null) {
            throw new IOException("the EPUB's package file has no manifest");
        }

        Element item = findImageCoverItem(opf);
        String type = item != null ? normalise(item.getAttribute("media-type")) : null;
        if (item != null && !"image/jpeg".equals(type) && !"image/png".equals(type)) {
            // A GIF, WebP or SVG cover: add a JPEG one beside it rather than rewrite references to it.
            removeProperty(item, "cover-image");
            item = null;
        }
        if (item == null) {
            item = addCoverItem(opf, manifest, opfFile.getParent());
            type = "image/jpeg";
        }
        setCoverMeta(opf, item.getAttribute("id"));

        String sniffed = sniff(image);
        byte[] bytes = type.equals(sniffed) ? image : encode(decoded, type);
        Path target = opfFile.getParent().resolve(decode(item.getAttribute("href"))).normalize();
        if (!target.startsWith(extractedDir)) {
            throw new IOException("the cover's path points outside the book");
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        String entry = extractedDir.relativize(target).toString().replace('\\', '/');
        return new Written(entry, bytes, decoded.getWidth(), decoded.getHeight());
    }

    /** The cover item if it's an image: via the cover meta, then cover-image, then a usual id. */
    static Element findImageCoverItem(Document opf) {
        List<Element> items = manifestItems(opf);
        String metaId = coverMetaId(opf);
        if (metaId != null) {
            for (Element item : items) {
                if (metaId.equals(item.getAttribute("id")) && isImage(item)) {
                    return item;
                }
            }
        }
        for (Element item : items) {
            if (hasProperty(item, "cover-image") && isImage(item)) {
                return item;
            }
        }
        for (Element item : items) {
            if (FALLBACK_IDS.contains(item.getAttribute("id")) && isImage(item)) {
                return item;
            }
        }
        return null;
    }

    /** Whatever the OPF calls the cover, image or not. */
    private static Element findDeclaredCoverItem(Document opf) {
        String metaId = coverMetaId(opf);
        for (Element item : manifestItems(opf)) {
            String id = item.getAttribute("id");
            if (id.equals(metaId) || hasProperty(item, "cover-image")) {
                return item;
            }
        }
        return null;
    }

    private static Check checkImage(byte[] bytes, String declaredType, String entry) {
        BufferedImage image = decode(bytes);
        if (image == null) {
            return Check.problem(Problem.UNREADABLE, bytes.length + " bytes", entry);
        }
        String actual = sniff(bytes);
        String declared = normalise(declaredType);
        if (actual != null && !actual.equals(declared)) {
            return Check.problem(Problem.WRONG_TYPE, "declared " + declaredType + " but it's " + actual, entry);
        }
        return new Check(null, null, entry, image.getWidth(), image.getHeight());
    }

    private static Element addCoverItem(Document opf, Element manifest, Path opfDir) {
        List<Element> items = manifestItems(opf);
        String id = NEW_COVER_NAME;
        String href = NEW_COVER_NAME + ".jpg";
        for (int n = 2; idTaken(items, id) || Files.exists(opfDir.resolve(href)); n++) {
            id = NEW_COVER_NAME + "-" + n;
            href = NEW_COVER_NAME + "-" + n + ".jpg";
        }
        for (Element other : items) {
            removeProperty(other, "cover-image");
        }
        Element item = opf.createElementNS(OPF_NS, prefixed(manifest, "item"));
        item.setAttribute("id", id);
        item.setAttribute("href", href);
        item.setAttribute("media-type", "image/jpeg");
        if (opf.getDocumentElement().getAttribute("version").startsWith("3")) {
            item.setAttribute("properties", "cover-image");
        }
        manifest.appendChild(item);
        return item;
    }

    private static void setCoverMeta(Document opf, String itemId) throws IOException {
        Element metadata = first(opf.getDocumentElement(), "metadata");
        if (metadata == null) {
            throw new IOException("the EPUB's package file has no metadata section");
        }
        for (Element meta : children(metadata, "meta")) {
            if ("cover".equals(meta.getAttribute("name"))) {
                meta.setAttribute("content", itemId);
                return;
            }
        }
        Element meta = opf.createElementNS(OPF_NS, prefixed(metadata, "meta"));
        meta.setAttribute("name", "cover");
        meta.setAttribute("content", itemId);
        metadata.appendChild(meta);
    }

    private static String coverMetaId(Document opf) {
        Element metadata = first(opf.getDocumentElement(), "metadata");
        if (metadata == null) {
            return null;
        }
        for (Element meta : children(metadata, "meta")) {
            if ("cover".equals(meta.getAttribute("name")) && !meta.getAttribute("content").isBlank()) {
                return meta.getAttribute("content").trim();
            }
        }
        return null;
    }

    private static List<Element> manifestItems(Document opf) {
        Element manifest = first(opf.getDocumentElement(), "manifest");
        return manifest != null ? children(manifest, "item") : List.of();
    }

    private static boolean isImage(Element item) {
        return normalise(item.getAttribute("media-type")).startsWith("image/");
    }

    private static boolean hasProperty(Element item, String property) {
        return Arrays.asList(item.getAttribute("properties").trim().split("\\s+")).contains(property);
    }

    private static void removeProperty(Element item, String property) {
        if (!hasProperty(item, property)) {
            return;
        }
        String rest = String.join(" ", Arrays.stream(item.getAttribute("properties").trim().split("\\s+"))
                .filter(p -> !p.equals(property)).toList());
        if (rest.isEmpty()) {
            item.removeAttribute("properties");
        } else {
            item.setAttribute("properties", rest);
        }
    }

    private static boolean idTaken(List<Element> items, String id) {
        return items.stream().anyMatch(item -> id.equals(item.getAttribute("id")));
    }

    /** Keeps the prefix the OPF already uses for its elements (some write {@code opf:item}). */
    private static String prefixed(Element parent, String localName) {
        return parent.getPrefix() != null ? parent.getPrefix() + ":" + localName : localName;
    }

    private static Element first(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String opfPath(ZipFile zip) throws Exception {
        Document container;
        try (InputStream in = zip.getInputStream(requireEntry(zip, "META-INF/container.xml"))) {
            container = SecureXmlUtils.createSecureDocumentBuilder(false).parse(in);
        }
        Node rootfile = container.getElementsByTagName("rootfile").item(0);
        if (rootfile == null) {
            throw new IOException("container.xml names no package file");
        }
        return ((Element) rootfile).getAttribute("full-path");
    }

    private static ZipEntry requireEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException(name + " is missing");
        }
        return entry;
    }

    /** The zip path of a manifest item, which is relative to the OPF. */
    private static String resolve(String opfPath, Element item) {
        String dir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        return Path.of("/" + dir + decode(item.getAttribute("href"))).normalize().toString().substring(1).replace('\\', '/');
    }

    private static String decode(String href) {
        return URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String normalise(String mediaType) {
        String type = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        return type.equals("image/jpg") ? "image/jpeg" : type;
    }

    /** The image type from its first bytes, or null if it's not one we recognise. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage image, String type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if ("image/png".equals(type)) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
