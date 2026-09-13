# Help screenshots

The in-app help (`booklore-ui/public/docs`) uses screenshots taken from a throwaway Trove instance
that holds nothing but public-domain sample books, so the pictures never show anyone's real library,
accounts or copyrighted cover art.

| File | What it does |
|---|---|
| `build-library.sh` | Downloads and assembles the sample library (below) |
| `sample-data.py` | Sets the sample books' details, author biographies and reading history through the API |
| `capture.mjs` | Drives the instance with Playwright and saves each screenshot as `booklore-ui/public/docs/images/<page>/<name>.jpg` |
| `link-images.mjs` | Points help pages' `<img>` tags at local files, matching them by name |

## The docs instance

A separate install on the same machine: its own PostgreSQL database and role (`trove_docs`), data
folder (`/srv/trove-docs`), port (6061), and the sample library mounted at `/books` (a symlink to
`/srv/trove-docs/library`), the same path the Docker instructions use. It runs the jar built from this
checkout with `TROVE_SELF_UPDATE=false`.

The admin account is a public-domain persona, Elinor Dashwood (`elinor`), created by the capture
script's `setup` scene on the first run. A second user, Marianne Dashwood, is created by the `devices`
scene for the permission screenshots.

The browser reaches the instance as `http://trove.example.com` (Chromium resolves that name to the
real address), so wherever Trove prints its own address (KOReader, OPDS, Kobo, OIDC redirect) the
screenshots show an example domain rather than `localhost`. Set `TROVE_DOCS_SITE` to use another.

## The sample library

All public domain:

- 32 EPUBs from [Project Gutenberg](https://www.gutenberg.org), including three series (Sherlock
  Holmes, Oz, Anne of Green Gables), with their scanned original covers.
- *The Art of War* as a PDF, printed with Playwright from Gutenberg's HTML edition (Lionel Giles's
  1910 translation).
- *The Marvelous Land of Oz Picture Book*: a CBZ of John R. Neill's 1904 illustrations, taken from the
  Gutenberg edition of *The Marvelous Land of Oz*.
- *The Happy Prince and Other Tales* as an M4B, from [LibriVox](https://librivox.org) via the
  Internet Archive.

Kept aside for scenes that add books: three more Gutenberg EPUBs for Bookdrop, and the
[Literata](https://github.com/googlefonts/literata) typeface (SIL Open Font License) for custom fonts.

Titles, series order, first publication years, genres, descriptions and author biographies were
written for these screenshots, and the reading history is sample data. Metadata was not fetched from
the online providers: they would bring in modern publishers' cover art and copy. Screens that do show
provider results (metadata search, Bookdrop, author search) have those results blurred.

## Screens from other software

Screens of other software (Authentik's admin pages, a Kobo and KOReader in use, Amazon and Hardcover
in a browser) can't come from the docs instance. They're the ones the BookLore documentation used,
copied from its archived site into `images/` so the help doesn't depend on the Wayback Machine,
with anything private or copyrighted blurred first: the Kobo shelf's book covers, a Kobo sync
token, a Hardcover API token, Amazon cookie values and a user name in a Finder window. The Authentik
screens show BookLore's own demo server. The two Trove settings screens from that set (where the
Amazon cookie and Hardcover token go) are captured afresh by the `providerTokens` scene.

## Running it

```sh
# once, in a scratch directory: Playwright for capture.mjs (and the PDF in build-library.sh)
npm init -y >/dev/null && npm i playwright@1.63.0 && npx playwright install chromium

# once: the sample library
scripts/docs-screenshots/build-library.sh /srv/trove-docs/library /srv/trove/docs-staging "$SCRATCH"

# start the docs instance, then from the scratch directory:
export TROVE_DOCS_URL=http://localhost:6061 TROVE_DOCS_PASSWORD=...
node /opt/trove/scripts/docs-screenshots/capture.mjs setup welcome library   # first run only
python3 /opt/trove/scripts/docs-screenshots/sample-data.py                  # once, after the scan
node /opt/trove/scripts/docs-screenshots/capture.mjs [scene ...]            # all scenes, or some
node /opt/trove/scripts/docs-screenshots/link-images.mjs
```

With no scene names, every scene runs in order. `TROVE_DOCS_SETTINGS_ONLY=name,name` limits the
`settings` scene to some of its screenshots. If a scene fails, a picture of the page at that moment is
saved as `failed-<scene>.png` in the current directory.

Scenes that change the instance put it back or clean up after an earlier run: the shelf, magic shelf,
tag, Bookdrop, email, icon, font, API token and Kobo shelf scenes remove what a previous run created
first. The table scene opens the delete confirmation but always cancels it and checks no book was
removed; the Bookdrop scene deletes the books it imports and checks the book count afterwards.

## Help pages

The pages are plain HTML sharing `docs.css` and `docs.js` (the sidebar of topics). To add a page,
copy an existing one, keep its `<main class="doc">` structure, and add it to `NAV` in `docs.js`.
