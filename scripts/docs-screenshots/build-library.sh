#!/usr/bin/env bash
# Builds the public-domain sample library the help screenshots are taken from (see README.md):
#   - 32 EPUBs from Project Gutenberg, filed as Author/Title.epub
#   - The Art of War as a PDF, printed from Gutenberg's HTML edition
#   - a CBZ of John R. Neill's plates from The Marvelous Land of Oz
#   - The Happy Prince and Other Tales as an M4B from LibriVox (via the Internet Archive)
# plus, in a separate folder, three more EPUBs for the Bookdrop scene and the Literata font (OFL).
#
# Usage: build-library.sh LIBRARY_DIR STAGING_DIR PLAYWRIGHT_DIR
#   PLAYWRIGHT_DIR is the scratch directory where playwright was installed for capture.mjs.
set -euo pipefail

LIBRARY=${1:?library directory}
STAGING=${2:?staging directory}
PLAYWRIGHT_DIR=${3:?directory with node_modules/playwright}
AGENT="Trove docs sample library (one-off download)"

# Gutenberg ebook numbers: Holmes, Oz, Anne and Alice series, and single novels.
LIBRARY_BOOKS="244 2097 1661 834 2852 108 55 54 486 45 47 51 1342 84 2701 345 11 12 35 36 120 98 1400 174 1260 158 514 215 236 16 164 103"
BOOKDROP_BOOKS="113 289 219"   # The Secret Garden, The Wind in the Willows, Heart of Darkness

mkdir -p "$LIBRARY" "$STAGING/fonts"
for id in $LIBRARY_BOOKS $BOOKDROP_BOOKS; do
  [ -s "$STAGING/pg$id.epub" ] && continue
  curl -sfL -A "$AGENT" -o "$STAGING/pg$id.epub" "https://www.gutenberg.org/ebooks/$id.epub3.images"
  sleep 2   # be polite to Gutenberg
done

# File the library's EPUBs under the author and title embedded in each.
LIBRARY="$LIBRARY" STAGING="$STAGING" IDS="$LIBRARY_BOOKS" python3 - <<'EOF'
import html, os, re, shutil, zipfile
library, staging = os.environ['LIBRARY'], os.environ['STAGING']
def clean(s): return re.sub(r'[\\/:*?"<>|]', '', s).strip().rstrip('.')
for book_id in os.environ['IDS'].split():
    path = os.path.join(staging, f'pg{book_id}.epub')
    opf = zipfile.ZipFile(path)
    x = opf.read([n for n in opf.namelist() if n.endswith('.opf')][0]).decode('utf-8', 'replace')
    title = html.unescape(re.search(r'<dc:title[^>]*>(.*?)</dc:title>', x, re.S).group(1)).strip()
    title = title.split('\n')[0].split(';')[0]
    m = re.search(r'<dc:creator[^>]*>(.*?)</dc:creator>', x, re.S)
    author = html.unescape(m.group(1)).strip() if m else 'Unknown'
    if ',' in author:  # "Doyle, Arthur Conan" -> "Arthur Conan Doyle"
        last, first = author.split(',', 1)
        author = f'{first.strip()} {last.strip()}'
    author = re.sub(r'\s*\(.*?\)', '', author)
    dest = os.path.join(library, clean(author), clean(title)[:80] + '.epub')
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copy(path, dest)
EOF

# The Art of War (Lionel Giles's 1910 translation), printed to PDF.
if [ ! -s "$LIBRARY/Sun Tzu/The Art of War.pdf" ]; then
  mkdir -p "$LIBRARY/Sun Tzu"
  curl -sfL -A "$AGENT" -o "$STAGING/artofwar.html" https://www.gutenberg.org/cache/epub/132/pg132-images.html
  (cd "$PLAYWRIGHT_DIR" && node - "$STAGING/artofwar.html" "$LIBRARY/Sun Tzu/The Art of War.pdf" <<'EOF'
const {chromium} = require(process.cwd() + '/node_modules/playwright');
(async () => {
  const [html, pdf] = process.argv.slice(2);
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('file://' + html, {waitUntil: 'load'});
  await page.pdf({path: pdf, format: 'A5', margin: {top: '18mm', bottom: '18mm', left: '15mm', right: '15mm'}, printBackground: true});
  await browser.close();
})();
EOF
  )
fi

# A picture book: the first 24 full-page plates from The Marvelous Land of Oz (Gutenberg 54).
LIBRARY="$LIBRARY" STAGING="$STAGING" python3 - <<'EOF'
import os, re, zipfile
library, staging = os.environ['LIBRARY'], os.environ['STAGING']
src = zipfile.ZipFile(os.path.join(staging, 'pg54.epub'))
def key(n): return [int(t) if t.isdigit() else t for t in re.split(r'(\d+)', n)]
images = [n for n in src.namelist() if n.lower().endswith(('.jpg', '.jpeg', '.png'))]
plates = [n for n in sorted(images, key=key) if src.getinfo(n).file_size > 120_000 and 'cover' not in n.lower()][:24]
out = os.path.join(library, 'John R. Neill', 'The Marvelous Land of Oz Picture Book.cbz')
os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as z:
    for i, name in enumerate(plates, 1):
        z.writestr(f'page{i:03d}{os.path.splitext(name)[1].lower()}', src.read(name))
    z.writestr('ComicInfo.xml', f'''<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <Title>The Marvelous Land of Oz Picture Book</Title>
  <Series>Oz Picture Books</Series>
  <Number>1</Number>
  <Year>1904</Year>
  <Writer>L. Frank Baum</Writer>
  <Penciller>John R. Neill</Penciller>
  <Summary>John R. Neill's illustrations for The Marvelous Land of Oz (1904), from the Project Gutenberg edition.</Summary>
  <PageCount>{len(plates)}</PageCount>
</ComicInfo>''')
EOF

# The LibriVox recording of The Happy Prince and Other Tales, with its cover, in a folder of its own.
HAPPY="$LIBRARY/Oscar Wilde/The Happy Prince and Other Tales"
if [ ! -s "$HAPPY/The Happy Prince and Other Tales.m4b" ]; then
  mkdir -p "$HAPPY"
  curl -sfL -o "$HAPPY/The Happy Prince and Other Tales.m4b" https://archive.org/download/hpot_librivox/HappyPrinceAndOtherTales_librivox.m4b
  curl -sfL -o "$HAPPY/cover.jpg" https://archive.org/download/hpot_librivox/Happy_Prince_and_Other_Tales_1103.jpg
fi

# Literata, an OFL-licensed reading typeface, for the custom fonts scene.
if [ ! -s "$STAGING/fonts/Literata.ttf" ]; then
  curl -sfL -o "$STAGING/fonts/Literata.ttf" "https://github.com/google/fonts/raw/main/ofl/literata/Literata%5Bopsz,wght%5D.ttf"
  curl -sfL -o "$STAGING/fonts/OFL-Literata.txt" https://github.com/google/fonts/raw/main/ofl/literata/OFL.txt
fi

echo "Library: $(find "$LIBRARY" -type f | wc -l) files in $LIBRARY"
