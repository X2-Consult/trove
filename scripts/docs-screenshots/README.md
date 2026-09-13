# Help screenshots

The in-app help (`booklore-ui/public/docs`) uses screenshots taken from a throwaway Trove instance
that holds nothing but public-domain sample books, so the pictures never show anyone's real library,
accounts or copyrighted cover art.

- `capture.mjs` drives that instance with Playwright and saves each screenshot as
  `booklore-ui/public/docs/images/<page>/<name>.jpg`, named after the image it replaces.
- `link-images.mjs` points each help page's `<img>` tags at the new files. Images with no capture yet,
  such as an identity provider's own admin screens, keep loading from the old docs site.

## The docs instance

A separate install on the same machine: its own PostgreSQL database and role (`trove_docs`), data
folder (`/srv/trove-docs`), port (6061), and the sample library mounted at `/books` (a symlink to
`/srv/trove-docs/library`), the same path the Docker instructions use. It runs the jar built from this
checkout with `TROVE_SELF_UPDATE=false`.

The admin account is a public-domain persona, Elinor Dashwood (`elinor`), created by the capture
script's `setup` scene on the first run.

## The sample library

All public domain:

- 32 EPUBs from [Project Gutenberg](https://www.gutenberg.org), including three series (Sherlock
  Holmes, Oz, Anne of Green Gables), with their scanned original covers.
- *The Art of War* as a PDF, rendered with Playwright from Gutenberg's HTML edition (Lionel Giles's
  1910 translation).
- *The Marvelous Land of Oz Picture Book*: a CBZ of John R. Neill's 1904 illustrations, taken from the
  Gutenberg edition of *The Marvelous Land of Oz*.
- *The Happy Prince and Other Tales* as an M4B, from [LibriVox](https://librivox.org) via the
  Internet Archive.

Titles, series order, first publication years, genres and one-line descriptions were set through the
API, and the reading history (sessions, progress, statuses, ratings) is sample data, also created
through the API, so the statistics pages have something to show. Metadata was not fetched from the
online providers: they would bring in modern publishers' cover art and copy. Screens that do show
provider results have those results blurred (`blurProviderContent`).

## Running it

```sh
# once, in a scratch directory
npm init -y >/dev/null && npm i playwright@1.63.0 && npx playwright install chromium
# then, from that directory
TROVE_DOCS_URL=http://localhost:6061 TROVE_DOCS_PASSWORD=... node /opt/trove/scripts/docs-screenshots/capture.mjs [scene ...]
node /opt/trove/scripts/docs-screenshots/link-images.mjs
```

Scenes that change the instance (creating a shelf, a magic shelf, highlights) remove what an earlier
run created first, so they can be run again. The table scene opens the delete confirmation but always
cancels it, and checks afterwards that no book was removed.
