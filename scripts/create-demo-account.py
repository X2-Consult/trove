#!/usr/bin/env python3
"""
Sets up a demo account for app-store review (or anyone who shouldn't see your own books):

  1. downloads a small public-domain library, two or three books in every format Trove reads,
  2. adds it to Trove as a library of its own,
  3. creates a user who can see only that library.

Books (all public domain):
  EPUB       Frankenstein, Alice's Adventures in Wonderland, The Great Gatsby   (Project Gutenberg)
  AZW3       The Adventures of Sherlock Holmes, The Metamorphosis              (Gutenberg's Kindle KF8)
  MOBI       Dr Jekyll and Mr Hyde, Dracula                                    (Gutenberg's older Kindle)
  PDF        The Art of War, A Modest Proposal                                 (made here from Gutenberg's text)
  FB2        The Yellow Wallpaper, The Call of the Wild                        (made here from Gutenberg's text)
  CBZ        The Marvelous Land of Oz and The Tale of Peter Rabbit as picture books, from the
             illustrations in Gutenberg's editions
  Audiobook  The Happy Prince (M4B) and three Poe tales (MP3 tracks)          (LibriVox, via the Internet Archive)

Run it on the Trove server as the user Trove runs as, so the files are readable:

  python3 scripts/create-demo-account.py
  python3 scripts/create-demo-account.py --folder /srv/trove/library/Demo_Books --username appreview

It asks for an admin login and the demo password. TROVE_ADMIN_USER, TROVE_ADMIN_PASSWORD and
DEMO_PASSWORD are used instead when set. Downloaded files are kept, so running it again only fetches
what's missing and reuses the library if it already exists.
"""
import argparse
import getpass
import html
import json
import os
import re
import secrets
import sys
import textwrap
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone

AGENT = "Trove demo library (one-off download)"
GUTENBERG = "https://www.gutenberg.org"

# (Gutenberg number, Gutenberg format, extension, author, title)
DOWNLOADS = [
    (84, "epub3.images", "epub", "Mary Shelley", "Frankenstein"),
    (11, "epub3.images", "epub", "Lewis Carroll", "Alice's Adventures in Wonderland"),
    (64317, "epub3.images", "epub", "F. Scott Fitzgerald", "The Great Gatsby"),
    (1661, "kf8.images", "azw3", "Arthur Conan Doyle", "The Adventures of Sherlock Holmes"),
    (5200, "kf8.images", "azw3", "Franz Kafka", "The Metamorphosis"),
    (43, "kindle.images", "mobi", "Robert Louis Stevenson", "The Strange Case of Dr Jekyll and Mr Hyde"),
    (345, "kindle.images", "mobi", "Bram Stoker", "Dracula"),
]
# Made from Gutenberg's plain text: (number, format, author first names, surname, title, genre)
FROM_TEXT = [
    (132, "pdf", "Sun", "Tzu", "The Art of War", "military"),
    (1080, "pdf", "Jonathan", "Swift", "A Modest Proposal", "prose_classic"),
    (1952, "fb2", "Charlotte Perkins", "Gilman", "The Yellow Wallpaper", "prose_classic"),
    (215, "fb2", "Jack", "London", "The Call of the Wild", "adventure"),
]
# Picture books from the illustrations in Gutenberg's illustrated editions:
# (number, writer, artist, title, series, year)
COMICS = [
    (54, "L. Frank Baum", "John R. Neill", "The Marvelous Land of Oz Picture Book", "Oz Picture Books", 1904),
    (14838, "Beatrix Potter", "Beatrix Potter", "The Tale of Peter Rabbit", "Peter Rabbit", 1902),
]
ARCHIVE = "https://archive.org/download"
HAPPY_PRINCE = ("hpot_librivox", "HappyPrinceAndOtherTales_librivox.m4b", "Happy_Prince_and_Other_Tales_1103.jpg")
POE_TALES = ("12_creepytales_1206_librivox", "12_Creepy_Tales_1206.jpg", [
    ("creepytalesbypoe_05_poe_64kb.mp3", "01 - The Masque of the Red Death.mp3"),
    ("creepytalesbypoe_06_poe_64kb.mp3", "02 - The Cask of Amontillado.mp3"),
    ("creepytalesbypoe_11_poe_64kb.mp3", "03 - The Raven.mp3"),
])


def log(msg):
    print(msg, flush=True)


def fetch(url, dest, polite=False):
    """Downloads url to dest unless it's already there."""
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=300) as response, open(dest + ".part", "wb") as out:
        while chunk := response.read(1 << 16):
            out.write(chunk)
    os.replace(dest + ".part", dest)
    if polite:
        time.sleep(2)  # Gutenberg asks for a gap between downloads
    return dest


def safe(name):
    return re.sub(r'[\\/:*?"<>|]', "", name).strip().rstrip(".")


def gutenberg_paragraphs(number, cache):
    """The book's text, without Gutenberg's header and licence, as a list of paragraphs."""
    path = fetch(f"{GUTENBERG}/cache/epub/{number}/pg{number}.txt", os.path.join(cache, f"pg{number}.txt"), polite=True)
    text = open(path, encoding="utf-8", errors="replace").read().replace("\r\n", "\n")
    start = re.search(r"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\n", text)
    end = re.search(r"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK", text)
    body = text[start.end() if start else 0:end.start() if end else len(text)]
    paragraphs = []
    for block in re.split(r"\n\s*\n", body):
        line = " ".join(part.strip() for part in block.strip().split("\n"))
        if line and not re.fullmatch(r"\[Illustration[^\]]*\]", line):
            paragraphs.append(line)
    return paragraphs


ITALIC = re.compile(r"_([^_]+)_")  # Gutenberg's plain text marks italics as _like this_


def is_heading(paragraph):
    return len(paragraph) < 80 and bool(re.match(r"(CHAPTER|Chapter|BOOK|PART)\b|[IVXLC]+\.?\s*$|[IVXLC]+\.\s+[A-Z]", paragraph))


# ---------------------------------------------------------------------------------------------
# PDF: a plain, book-sized PDF written directly, using the standard Times fonts (no libraries).
# ---------------------------------------------------------------------------------------------
def plain_letter(ch):
    """A character the standard PDF fonts can draw: itself, or its letter without the accent."""
    try:
        ch.encode("cp1252")
        return ch
    except UnicodeEncodeError:
        base = unicodedata.normalize("NFKD", ch)[:1]
        return base if base and base.isascii() else "?"


def pdf_text(s):
    raw = "".join(plain_letter(ch) for ch in s).encode("cp1252", errors="replace")
    return "(" + raw.decode("latin-1").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"


def make_pdf(dest, title, author, paragraphs):
    width, height, margin, size, leading = 420, 595, 48, 11, 15
    per_line = int((width - 2 * margin) / (size * 0.46))
    lines_per_page = int((height - 2 * margin - 20) / leading)

    lines = []
    for paragraph in paragraphs:
        if is_heading(paragraph):
            lines += ["", ("B", paragraph), ""]
        else:
            lines += textwrap.wrap(ITALIC.sub(r"\1", paragraph), per_line) + [""]
    pages = [lines[i:i + lines_per_page] for i in range(0, len(lines), lines_per_page)]

    streams = [
        f"BT /F2 20 Tf {margin} {height - 200} Td {pdf_text(title)} Tj ET\n"
        f"BT /F1 13 Tf {margin} {height - 232} Td {pdf_text(author)} Tj ET"
    ]
    for number, page in enumerate(pages, 1):
        body = [f"BT /F1 {size} Tf {leading} TL {margin} {height - margin - size} Td"]
        for line in page:
            if isinstance(line, tuple):
                body.append(f"/F2 {size} Tf {pdf_text(line[1])} Tj T* /F1 {size} Tf")
            else:
                body.append(f"{pdf_text(line)} Tj T*")
        body.append("ET")
        body.append(f"BT /F1 9 Tf {width / 2 - 6} {margin / 2} Td {pdf_text(str(number))} Tj ET")
        streams.append("\n".join(body))

    objects = []

    def add(obj):
        objects.append(obj)
        return len(objects)

    catalog = add(None)
    pages_obj = add(None)
    regular = add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>")
    bold = add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>")
    info = add(f"<< /Title {pdf_text(title)} /Author {pdf_text(author)} /Producer (Trove demo library) >>")
    kids = []
    for stream in streams:
        data = stream.encode("latin-1")
        content = add(f"<< /Length {len(data)} >>\nstream\n".encode("latin-1") + data + b"\nendstream")
        kids.append(add(f"<< /Type /Page /Parent {pages_obj} 0 R /MediaBox [0 0 {width} {height}] "
                        f"/Resources << /Font << /F1 {regular} 0 R /F2 {bold} 0 R >> >> /Contents {content} 0 R >>"))
    objects[catalog - 1] = f"<< /Type /Catalog /Pages {pages_obj} 0 R >>"
    objects[pages_obj - 1] = f"<< /Type /Pages /Kids [{' '.join(f'{k} 0 R' for k in kids)}] /Count {len(kids)} >>"

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        body = obj if isinstance(obj, bytes) else obj.encode("latin-1")
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root {catalog} 0 R /Info {info} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    with open(dest, "wb") as f:
        f.write(out)


# ---------------------------------------------------------------------------------------------
# FB2: FictionBook XML, a chapter per section where the text has chapter headings.
# ---------------------------------------------------------------------------------------------
def make_fb2(dest, first, last, title, genre, paragraphs):
    esc = lambda s: html.escape(s, quote=False)
    sections, current = [], {"title": None, "paragraphs": []}
    for paragraph in paragraphs:
        if is_heading(paragraph):
            if current["paragraphs"]:
                sections.append(current)
            current = {"title": paragraph, "paragraphs": []}
        else:
            current["paragraphs"].append(paragraph)
    if current["paragraphs"]:
        sections.append(current)
    body = []
    for section in sections:
        heading = f"<title><p>{esc(section['title'])}</p></title>" if section["title"] else ""
        paragraphs_xml = "".join("<p>" + ITALIC.sub(r"<emphasis>\1</emphasis>", esc(p)) + "</p>" for p in section["paragraphs"])
        body.append("<section>" + heading + paragraphs_xml + "</section>")
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description>
<title-info><genre>{genre}</genre><author><first-name>{esc(first)}</first-name><last-name>{esc(last)}</last-name></author><book-title>{esc(title)}</book-title><lang>en</lang></title-info>
<document-info><author><nickname>Trove</nickname></author><program-used>Trove demo library script</program-used><date>{datetime.now(timezone.utc):%Y-%m-%d}</date><id>trove-demo-{secrets.token_hex(8)}</id><version>1.0</version></document-info>
</description>
<body><title><p>{esc(first)} {esc(last)}</p><p>{esc(title)}</p></title>{''.join(body)}</body>
</FictionBook>
"""
    with open(dest, "w", encoding="utf-8") as f:
        f.write(xml)


def make_cbz(dest, epub_path, book_title, series, writer, artist, year):
    src = zipfile.ZipFile(epub_path)
    natural = lambda n: [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", n)]
    images = sorted((n for n in src.namelist() if n.lower().endswith((".jpg", ".jpeg", ".png")) and "cover" not in n.lower()), key=natural)
    # Full-page plates are the big images; a picture book's illustrations are all smaller.
    for threshold in (120_000, 60_000, 25_000):
        plates = [n for n in images if src.getinfo(n).file_size > threshold][:30]
        if len(plates) >= 8:
            break
    if not plates:
        raise SystemExit(f"No illustrations found in {epub_path}")
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_STORED) as z:
        for i, name in enumerate(plates, 1):
            z.writestr(f"page{i:03d}{os.path.splitext(name)[1].lower()}", src.read(name))
        z.writestr("ComicInfo.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <Title>{html.escape(book_title)}</Title>
  <Series>{html.escape(series)}</Series>
  <Year>{year}</Year>
  <Writer>{html.escape(writer)}</Writer>
  <Penciller>{html.escape(artist)}</Penciller>
  <Summary>{html.escape(artist)}'s illustrations from the Project Gutenberg edition.</Summary>
  <PageCount>{len(plates)}</PageCount>
</ComicInfo>""")


def build_library(folder):
    cache = os.path.join(folder, ".downloads")
    os.makedirs(cache, exist_ok=True)
    for number, fmt, ext, author, title in DOWNLOADS:
        dest = os.path.join(folder, safe(author), f"{safe(title)}.{ext}")
        log(f"  {ext.upper():5} {title}")
        fetch(f"{GUTENBERG}/ebooks/{number}.{fmt}", dest, polite=True)
    for number, ext, first, last, title, genre in FROM_TEXT:
        dest = os.path.join(folder, safe(f"{first} {last}"), f"{safe(title)}.{ext}")
        log(f"  {ext.upper():5} {title}")
        if not os.path.exists(dest):
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            paragraphs = gutenberg_paragraphs(number, cache)
            (make_pdf if ext == "pdf" else make_fb2)(dest, *((title, f"{first} {last}") if ext == "pdf" else (first, last, title, genre)), paragraphs)
    for number, writer, artist, title, series, year in COMICS:
        dest = os.path.join(folder, safe(artist), f"{safe(title)}.cbz")
        log(f"  CBZ   {title}")
        if not os.path.exists(dest):
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            make_cbz(dest, fetch(f"{GUTENBERG}/ebooks/{number}.epub3.images", os.path.join(cache, f"pg{number}.epub"), polite=True),
                     title, series, writer, artist, year)
    log("  M4B   The Happy Prince and Other Tales")
    item, audio, cover = HAPPY_PRINCE
    happy = os.path.join(folder, "Oscar Wilde", "The Happy Prince and Other Tales")
    fetch(f"{ARCHIVE}/{item}/{audio}", os.path.join(happy, "The Happy Prince and Other Tales.m4b"))
    fetch(f"{ARCHIVE}/{item}/{cover}", os.path.join(happy, "cover.jpg"))
    log("  MP3   Three Tales by Edgar Allan Poe")
    item, cover, tracks = POE_TALES
    poe = os.path.join(folder, "Edgar Allan Poe", "Three Tales")
    for source, name in tracks:
        fetch(f"{ARCHIVE}/{item}/{source}", os.path.join(poe, name))
    fetch(f"{ARCHIVE}/{item}/{cover}", os.path.join(poe, "cover.jpg"))
    # The download cache is hidden from Trove (a leading dot), but it's not needed any more.
    for name in os.listdir(cache):
        os.remove(os.path.join(cache, name))
    os.rmdir(cache)


# ---------------------------------------------------------------------------------------------
# Trove API
# ---------------------------------------------------------------------------------------------
class Trove:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                detail = json.loads(detail).get("message", detail)
            except ValueError:
                pass
            raise SystemExit(f"Trove refused {method} {path}: {e.code} {detail}")

    def login(self, username, password):
        self.token = self.call("POST", "/api/v1/auth/login", {"username": username, "password": password})["accessToken"]


def main():
    parser = argparse.ArgumentParser(description="Create a demo library and a user who can see only it.")
    parser.add_argument("--folder", default="/srv/trove/library/Demo_Books", help="where the demo books go (default %(default)s)")
    parser.add_argument("--url", default="http://localhost:6060", help="Trove's address from this machine (default %(default)s)")
    parser.add_argument("--library-name", default="Demo", help="name of the demo library (default %(default)s)")
    parser.add_argument("--username", default="appreview", help="the demo user's login (default %(default)s)")
    parser.add_argument("--name", default="App Review", help="the demo user's display name")
    parser.add_argument("--email", default="appreview@example.com", help="the demo user's email address")
    parser.add_argument("--files-only", action="store_true", help="only download the books; don't touch Trove")
    args = parser.parse_args()
    folder = os.path.abspath(args.folder)

    log(f"Downloading the demo books into {folder} ...")
    build_library(folder)
    log(f"Done: {sum(len(f) for _, _, f in os.walk(folder))} files.")
    if args.files_only:
        return

    trove = Trove(args.url)
    admin_user = os.environ.get("TROVE_ADMIN_USER") or input("Trove admin username: ")
    admin_password = os.environ.get("TROVE_ADMIN_PASSWORD") or getpass.getpass("Trove admin password: ")
    trove.login(admin_user, admin_password)

    libraries = trove.call("GET", "/api/v1/libraries")
    library = next((l for l in libraries if l["name"] == args.library_name
                    or any(os.path.abspath(p["path"]) == folder for p in l.get("paths", []))), None)
    if library:
        log(f"Using the existing library '{library['name']}'.")
    else:
        library = trove.call("POST", "/api/v1/libraries", {
            "name": args.library_name, "paths": [{"path": folder}], "watch": True,
            "icon": "pi pi-book", "iconType": "PRIME_NG",
            "organizationMode": "BOOK_PER_FILE", "metadataSource": "EMBEDDED",
            "formatPriority": [], "allowedFormats": []})
        log(f"Created the library '{library['name']}'; Trove is reading the books now.")

    users = trove.call("GET", "/api/v1/users")
    if any(u["username"] == args.username for u in users):
        log(f"The user '{args.username}' already exists, so it was left as it is. Check in Settings > Users that it has only the '{library['name']}' library.")
        return

    password = os.environ.get("DEMO_PASSWORD") or getpass.getpass(f"Password for '{args.username}' (Enter to generate one): ") \
        or secrets.token_urlsafe(12)
    if len(password) < 8:
        raise SystemExit("The demo password needs at least 8 characters.")
    temporary = secrets.token_urlsafe(16)
    permissions = {k: False for k in (
        "permissionUpload", "permissionEditMetadata", "permissionManageLibrary", "permissionEmailBook",
        "permissionDeleteBook", "permissionSyncKoreader", "permissionSyncKobo", "permissionAdmin",
        "permissionManageMetadataConfig", "permissionAccessBookdrop", "permissionAccessLibraryStats",
        "permissionAccessUserStats", "permissionAccessTaskManager", "permissionManageGlobalPreferences",
        "permissionManageIcons", "permissionManageFonts", "permissionBulkAutoFetchMetadata",
        "permissionBulkCustomFetchMetadata", "permissionBulkEditMetadata", "permissionBulkRegenerateCover",
        "permissionMoveOrganizeFiles", "permissionBulkLockUnlockMetadata", "permissionBulkResetBookloreReadProgress",
        "permissionBulkResetKoReaderReadProgress", "permissionBulkResetBookReadStatus")}
    permissions.update(permissionDownload=True, permissionAccessOpds=True)
    trove.call("POST", "/api/v1/auth/register", {
        "username": args.username, "password": temporary, "name": args.name, "email": args.email,
        "selectedLibraries": [library["id"]], **permissions})

    # New users must change their password at first sign-in; doing it here means the reviewer
    # can sign straight in with the password below.
    demo = Trove(args.url)
    demo.login(args.username, temporary)
    demo.call("PUT", "/api/v1/users/change-password", {"currentPassword": temporary, "newPassword": password})
    demo.login(args.username, password)

    expected = len(DOWNLOADS) + len(FROM_TEXT) + len(COMICS) + 2
    for _ in range(40):
        books = demo.call("GET", "/api/v1/books")
        if len(books) >= expected:
            break
        time.sleep(3)
    formats = {}
    for book in books:
        kind = (book.get("primaryFile") or {}).get("bookType", "?")
        formats[kind] = formats.get(kind, 0) + 1
    log(f"\nThe demo user sees {len(books)} books: " + ", ".join(f"{n} {k}" for k, n in sorted(formats.items())))
    log(f"\nDemo account for the reviewer:\n  Server:   your Trove address (for example https://trove.example.com)\n"
        f"  Username: {args.username}\n  Password: {password}\n"
        f"It can read and download the '{library['name']}' library's books and use OPDS, and nothing else;\n"
        f"change its permissions in Settings > Users if your app needs more.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
