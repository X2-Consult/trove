// Captures the screenshots used by the in-app help (booklore-ui/public/docs/images/<page>/<name>.jpg)
// from a running Trove instance set up for the purpose: a fresh install whose only library is
// public-domain sample books (see README.md next to this script). Screenshots are named after the
// images they replace, so a page's <img> tags can be pointed at them mechanically.
//
// Setup (once, in any scratch directory - playwright is resolved from the current directory):
//   npm init -y >/dev/null && npm i playwright@1.63.0 && npx playwright install chromium
// Run:
//   TROVE_DOCS_URL=http://localhost:6061 TROVE_DOCS_PASSWORD=... node /opt/trove/scripts/docs-screenshots/capture.mjs [scene ...]
// With no scene names, every scene runs in order. "setup" only works on a fresh install.

import {createRequire} from 'node:module';
import {mkdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const require = createRequire(path.join(process.cwd(), 'noop.js'));
const {chromium} = require('playwright');

const BASE = (process.env.TROVE_DOCS_URL || 'http://localhost:6061').replace(/\/$/, '');
// The address the browser uses, which is what Trove shows wherever it prints its own URL (KOReader and
// OPDS addresses, for example). The browser resolves it to BASE, so screenshots never show localhost.
const SITE = (process.env.TROVE_DOCS_SITE || 'http://trove.example.com').replace(/\/$/, '');
const PASSWORD = process.env.TROVE_DOCS_PASSWORD;
const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../booklore-ui/public/docs/images');
// A public-domain persona for the admin account shown in screenshots.
export const ADMIN = {username: 'elinor', name: 'Elinor Dashwood', email: 'elinor@example.com'};
const VIEWPORT = {width: 1440, height: 900};

if (!PASSWORD) {
  console.error('Set TROVE_DOCS_PASSWORD (the admin password to create or log in with).');
  process.exit(2);
}

/**
 * Saves a JPEG of the page, an element, or a region, as images/<page>/<name>.jpg. The mouse is moved
 * out of the way first (hover effects), and notification toasts are hidden unless keepToasts is set.
 */
async function shot(page, pageSlug, name, {element, clip, fullPage = false, keepToasts = false, hover = false} = {}) {
  const file = path.join(OUT, pageSlug, `${name}.jpg`);
  mkdirSync(path.dirname(file), {recursive: true});
  if (!hover) await page.mouse.move(VIEWPORT.width - 5, VIEWPORT.height - 5);
  await page.evaluate(hide => document.documentElement.classList.toggle('docs-hide-toasts', hide), !keepToasts);
  await page.waitForTimeout(500); // let animations settle
  const options = {path: file, type: 'jpeg', quality: 85, fullPage, animations: 'disabled'};
  if (element) {
    await (typeof element === 'string' ? page.locator(element).first() : element).screenshot(options);
  } else {
    await page.screenshot(clip ? {...options, clip} : options);
  }
  console.log(`  ${pageSlug}/${name}.jpg`);
}

// The login page's password field is a PrimeNG component wrapping the actual input.
const LOGIN_PASSWORD = '#password input, input#password';

async function login(page) {
  await page.goto(`${SITE}/login`, {waitUntil: 'networkidle'});
  await page.fill('#username', ADMIN.username);
  await page.fill(LOGIN_PASSWORD, PASSWORD);
  await page.click('button[type=submit]');
  await page.waitForURL(url => !url.toString().includes('/login'), {timeout: 30000});
  await page.waitForLoadState('networkidle');
}

async function open(page, route) {
  await page.goto(`${SITE}${route}`, {waitUntil: 'networkidle'});
  await page.addStyleTag({content: '.docs-hide-toasts .p-toast { display: none !important; }'});
  await page.waitForTimeout(800);
}

const scenes = {
  // First run: the admin account form, the login page, and the empty dashboard.
  async setup(page) {
    await open(page, '/setup');
    await page.fill('#username', ADMIN.username);
    await page.fill('#name', ADMIN.name);
    await page.fill('#email', ADMIN.email);
    await page.fill('#password', PASSWORD);
    await page.fill('#confirmPassword', PASSWORD);
    await shot(page, 'initial-setup', 'admin-user');
    await page.click('button[type=submit]');
    await page.waitForURL(url => url.toString().includes('/login'), {timeout: 30000});
    await page.waitForLoadState('networkidle');
    await page.fill('#username', ADMIN.username);
    await shot(page, 'initial-setup', 'login');
  },

  // The dashboard before any library exists.
  async welcome(page) {
    await open(page, '/dashboard');
    await shot(page, 'initial-setup', 'empty-dashboard');
    await shot(page, 'library/setup-first-library', 'click-add-a-library');
  },

  // Creates the "Classics" library from /books, capturing the dialog, folder picker and first scan.
  async library(page) {
    await open(page, '/dashboard');
    await page.getByRole('button', {name: /Create Your Library/}).click();
    await page.locator('.library-creator input.input-full').fill('Classics');
    await shot(page, 'library/setup-first-library', 'new-library-dialog');

    await page.click('.add-folder-trigger');
    await page.waitForSelector('.directory-picker .directory-item');
    await page.locator('.directory-picker input[pInputText], .directory-picker input').first().fill('books');
    await page.waitForTimeout(600);
    await page.locator('.directory-item', {hasText: 'books'}).first().locator('p-checkbox').click();
    await shot(page, 'library/setup-first-library', 'library-folder-picker');
    await page.getByRole('button', {name: 'Select Directories'}).click();
    await page.waitForTimeout(500);

    await page.locator('.library-creator .dialog-footer button').last().click();
    await page.waitForTimeout(6000); // the first books appear as the scan works through the folder
    await shot(page, 'library/setup-first-library', 'library-processing');
  },

  // The library in grid view: display settings, series grouping, sorting, search and filters.
  async grid(page) {
    // Scoped to the book browser's own header: the app's top bar uses the same classes.
    const toolbar = icon => page.locator(`.book-browser-header .topbar-item:has(i.${icon})`).first();
    const search = page.locator('.book-browser-header input').first();
    await open(page, '/all-books');
    await shot(page, 'book-browser/grid', 'overview');

    await toolbar('pi-cog').click();
    await page.waitForSelector('.display-settings-popover');
    await shot(page, 'book-browser/grid', 'cover-size-slider');
    const collapse = page.locator('.display-settings-popover p-checkbox').first();
    await collapse.click(); // group series into one card
    await page.keyboard.press('Escape');
    await page.waitForTimeout(800);
    await shot(page, 'book-browser/grid', 'series-collapsed');
    await toolbar('pi-cog').click();
    await page.waitForSelector('.display-settings-popover');
    await page.locator('.display-settings-popover p-checkbox').first().click(); // back to one card per book
    await page.keyboard.press('Escape');
    await page.waitForTimeout(800);
    await shot(page, 'book-browser/grid', 'series-expanded');

    await toolbar('pi-sort').click();
    await page.waitForTimeout(600);
    await shot(page, 'book-browser/grid', 'sort-dropdown');
    await page.keyboard.press('Escape');

    await search.fill('Holmes');
    await page.waitForTimeout(1500);
    await shot(page, 'book-browser/grid', 'search-bar');
    await search.fill('');
    await page.waitForTimeout(800);

    await page.getByText('Genre', {exact: true}).first().click();
    await page.waitForTimeout(700);
    await shot(page, 'book-browser/grid', 'sidebar-filters');
  },

  // The library in table view: columns, sorting, search, selection and the delete confirmation
  // (which is always cancelled).
  async table(page) {
    const header = page.locator('.book-browser-header');
    const toolbar = icon => header.locator(`.topbar-item:has(i.${icon})`).first();
    // The view toggle shows the icon of the other view: pi-objects-column in grid view, pi-table in table view.
    const viewToggle = header.locator('.topbar-item:has(i.pi-objects-column), .topbar-item:has(i.pi-table)').first();
    const search = header.locator('input').first();
    const booksBefore = await bookCount(page);

    await open(page, '/all-books');
    await shot(page, 'book-browser/table', 'toggle-grid-state', {element: header});
    await viewToggle.click();
    await page.waitForSelector('p-table, .p-datatable');
    await page.waitForTimeout(1000);
    await shot(page, 'book-browser/table', 'toggle-table-state', {element: header});

    await toolbar('pi-eye').click();
    await page.waitForSelector('.column-popover-content');
    await shot(page, 'book-browser/table', 'table-visible-columns');
    await page.keyboard.press('Escape');

    await page.locator('.p-datatable th', {hasText: 'Title'}).first().click();
    await page.waitForTimeout(800);
    await shot(page, 'book-browser/table', 'table-sorting');

    await search.fill('Oz');
    await page.waitForTimeout(1500);
    await shot(page, 'book-browser/table', 'table-search');
    await search.fill('');
    await page.waitForTimeout(1000);
    await shot(page, 'book-browser/table', 'sidebar-default');

    await page.locator('p-tableHeaderCheckbox').first().click();
    await page.waitForTimeout(800);
    await shot(page, 'book-browser/table', 'table-select-all');
    await page.locator('p-tableHeaderCheckbox').first().click(); // clear the selection

    const rows = page.locator('p-tableCheckbox');
    for (let i = 0; i < 3; i++) await rows.nth(i).click();
    await page.waitForTimeout(600);
    await shot(page, 'book-browser/table', 'delete-menu');
    await page.locator('button:has(.pi-trash)').first().click();
    await page.waitForTimeout(800);
    await shot(page, 'book-browser/table', 'delete-confirm');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(800);
    const cancel = page.getByRole('button', {name: /cancel|no/i});
    if (await cancel.first().isVisible().catch(() => false)) await cancel.first().click();
    await page.locator('button:has(.pi-times)').last().click(); // clear the selection
    await page.waitForTimeout(600);

    await viewToggle.click(); // leave the library in grid view
    await page.waitForTimeout(800);
    if (await bookCount(page) !== booksBefore) throw new Error('A book was deleted while capturing the table view');
  },
};

Object.assign(scenes, {
  // The dashboard with reading activity, and its layout settings.
  async dashboard(page) {
    await open(page, '/dashboard');
    await shot(page, 'dashboard', 'dashboard-overview');
    await page.locator('.dashboard-container button:has(.pi-cog), button:has(.pi-cog)').last().click();
    await page.waitForTimeout(900);
    await shot(page, 'dashboard', 'dashboard-settings');
  },

  // A book's page: details, the metadata editor, its sidecar file, reading sessions and reviews.
  async book(page) {
    const id = await bookId(page, 'The Hound of the Baskervilles');
    await open(page, `/book/${id}`);
    await shot(page, 'metadata/metadata-center', 'book-details');
    await page.getByText('Reading Sessions', {exact: true}).click();
    await page.waitForTimeout(1200);
    await scrollBy(page, 520);
    await shot(page, 'metadata/metadata-center', 'reading-sessions-tab');
    await page.getByText('Reviews', {exact: true}).click();
    await page.waitForTimeout(1200);
    await shot(page, 'metadata/metadata-center', 'reviews-tab');
    await scrollBy(page, -2000);
    await page.getByText('Edit Metadata', {exact: true}).click();
    await page.waitForTimeout(1200);
    await shot(page, 'metadata/metadata-center', 'edit-metadata');
    await page.getByText('Sidecar', {exact: true}).click();
    await page.waitForTimeout(1200);
    await shot(page, 'metadata/metadata-center', 'sidecar');
  },

  // Library and personal reading statistics.
  async stats(page) {
    await open(page, '/library-stats');
    await page.waitForTimeout(2500); // charts animate in
    await shot(page, 'library-stats', 'library-stats-overview');
    await scrollToHeading(page, 'Page Count Distribution');
    await shot(page, 'library-stats', 'library-stats-charts');

    await open(page, '/reading-stats');
    await page.waitForTimeout(2500);
    for (const [heading, name] of [
      ['Reading Completion Race', 'reading-stats-completion-race'],
      ['Session Archetypes', 'reading-stats-session-archetypes'],
      ['Reading Survival Curve', 'reading-stats-survival-length'],
      ['Book Flow', 'reading-stats-book-flow'],
      ['Reading Debt Waterfall', 'reading-stats-debt-era'],
    ]) {
      await scrollToHeading(page, heading);
      await shot(page, 'reading-stats', name);
    }
  },
});

/** GETs an API path as the signed-in user; throws on an error response. */
async function apiGet(page, apiPath) {
  const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
  if (!token) throw new Error('Not signed in: no access token in local storage');
  const response = await page.request.get(`${BASE}/api/v1${apiPath}`, {headers: {Authorization: `Bearer ${token}`}});
  if (!response.ok()) throw new Error(`GET ${apiPath} failed: HTTP ${response.status()}`);
  return response.json();
}

/** The "+" beside a sidebar section heading (Libraries, Shelves, Magic Shelves). */
function sidebarAdd(page, label) {
  return page.locator('.root-item-label-container', {has: page.locator('div', {hasText: new RegExp(`^${label}$`, 'i')})})
    .locator('.plus-icon');
}

Object.assign(scenes, {
  // Creating a shelf and putting books on it, one at a time and several at once.
  async shelves(page) {
    await deleteShelvesNamed(page, 'Summer Reading'); // so the scene can be run again
    await open(page, '/all-books');
    await sidebarAdd(page, 'Shelves').click();
    await page.fill('#shelfName', 'Summer Reading');
    await shot(page, 'shelf', 'create-form');
    await page.getByRole('button', {name: 'Create Shelf'}).click();
    await page.waitForTimeout(1500);
    await shot(page, 'shelf', 'create-confirm', {keepToasts: true});

    const cards = page.locator('app-book-card');
    await cards.first().getByRole('button', {name: 'Book actions menu'}).click();
    await page.waitForTimeout(700);
    await shot(page, 'shelf', 'assign-single-book');
    await page.keyboard.press('Escape');

    for (let i = 0; i < 4; i++) {
      await cards.nth(i).hover();
      await cards.nth(i).locator('.select-checkbox').click();
    }
    await page.waitForTimeout(600);
    await shot(page, 'shelf', 'assign-multiple-books');
    await page.locator('button:has(.pi-bookmark-fill)').first().click();
    await page.waitForTimeout(1000);
    const dialog = page.locator('.p-dialog').last();
    await dialog.getByText('Summer Reading').first().click();
    await shot(page, 'shelf', 'assign-dialog');
    await dialog.getByRole('button', {name: 'Save Changes'}).click();
    await page.waitForTimeout(1200);
    await shot(page, 'shelf', 'sidebar-listing', {element: '.layout-sidebar, app-sidebar, .sidebar'});
  },

  // A magic shelf built from rules: every book whose genre is Mystery or Gothic.
  async magicShelf(page) {
    // Magic shelves live at /api/magic-shelves, without the /v1 the other endpoints have.
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    const existing = await (await page.request.get(`${BASE}/api/magic-shelves`, {headers})).json();
    for (const shelf of existing.filter(s => s.name === 'Mysteries & Gothic')) {
      await page.request.delete(`${BASE}/api/magic-shelves/${shelf.id}`, {headers});
    }
    await open(page, '/all-books');
    await sidebarAdd(page, 'Magic Shelves').click();
    await page.fill('#shelfName', 'Mysteries & Gothic');
    await shot(page, 'magic-shelf', 'magic-shelf-1');
    const dialog = page.locator('.p-dialog').last();
    await dialog.getByRole('button', {name: 'Import'}).click();
    await dialog.locator('textarea').fill(JSON.stringify({
      type: 'group', join: 'or',
      rules: [{type: 'rule', field: 'categories', operator: 'includes_any', value: ['Mystery', 'Gothic']}],
    }));
    await dialog.locator('button', {hasText: /^Apply$/}).click();
    await page.waitForTimeout(1000);
    await shot(page, 'magic-shelf', 'magic-shelf-2');
    await dialog.getByRole('button', {name: 'Save Filter'}).click();
    await page.waitForTimeout(1500);
  },
});

/** Deletes the signed-in user's shelves with this name (not the books on them). */
async function deleteShelvesNamed(page, name) {
  const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
  for (const shelf of (await apiGet(page, '/shelves')).filter(s => s.name === name)) {
    const response = await page.request.delete(`${BASE}/api/v1/shelves/${shelf.id}`, {headers: {Authorization: `Bearer ${token}`}});
    if (!response.ok()) throw new Error(`Couldn't delete shelf ${shelf.id}: HTTP ${response.status()}`);
  }
}

/**
 * Blurs content that came from online metadata providers - result covers or photos and long
 * descriptions - inside `container`, so screenshots show the layout without reproducing other
 * people's cover art or text.
 */
async function blurProviderContent(page, container) {
  await page.locator(container).first().evaluate(root => {
    root.querySelectorAll('img').forEach(img => { img.style.filter = 'blur(10px)'; });
    root.querySelectorAll('p, div, span').forEach(el => {
      const ownText = [...el.childNodes].filter(n => n.nodeType === Node.TEXT_NODE).map(n => n.textContent).join('').trim();
      if (ownText.length > 90) el.style.filter = 'blur(4px)';
    });
  });
}

Object.assign(scenes, {
  async series(page) {
    await open(page, '/series');
    await shot(page, 'series', 'browse-all-series');
  },

  // The authors list, an author's page, the edit form, and an author search (provider results blurred).
  async authors(page) {
    await open(page, '/authors');
    await shot(page, 'authors', 'browse-all-authors');
    const doyle = (await apiGet(page, '/authors')).find(a => a.name === 'Arthur Conan Doyle');
    await open(page, `/author/${doyle.id}`);
    await shot(page, 'authors', 'author-details');
    await page.getByText('Edit Details', {exact: true}).click();
    await page.waitForTimeout(1000);
    await shot(page, 'authors', 'edit-details');
    await page.getByText('Search Author', {exact: true}).click();
    await page.waitForTimeout(8000); // providers answer in their own time
    await blurProviderContent(page, 'app-author-detail, .author-detail, main');
    await shot(page, 'authors', 'search-author');
  },
});

/** The frame holding the EPUB reader's page content (foliate renders each section in an iframe). */
async function bookFrame(page) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const frame = page.frames().find(f => f !== page.mainFrame() && f.url() !== 'about:blank');
    if (frame && await frame.locator('p').count().catch(() => 0)) return frame;
    await page.waitForTimeout(500);
  }
  throw new Error('No book content frame found in the EPUB reader');
}

/** The reader hides its toolbars while you read; hovering near an edge brings them back. */
async function readerToolbar(page, title, edge = 'top') {
  await page.mouse.move(VIEWPORT.width / 2, edge === 'top' ? 8 : VIEWPORT.height - 8);
  await page.waitForTimeout(600);
  await page.getByTitle(title, {exact: true}).first().click();
  await page.waitForTimeout(900);
}

Object.assign(scenes, {
  // The EPUB reader: a page, contents, the location popover, settings panels and a highlight with notes.
  async epubReader(page) {
    const id = await bookId(page, 'Pride and Prejudice');
    // Start from no highlights, so running the scene again doesn't pile them up.
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    for (const annotation of await apiGet(page, `/annotations/book/${id}`)) {
      await page.request.delete(`${BASE}/api/v1/annotations/${annotation.id}`, {headers});
    }
    // Notes added in the EPUB reader live in the v2 notes API.
    const notes = await (await page.request.get(`${BASE}/api/v2/book-notes/book/${id}`, {headers})).json();
    for (const note of notes) {
      await page.request.delete(`${BASE}/api/v2/book-notes/${note.id}`, {headers});
    }
    await open(page, `/ebook-reader/book/${id}`);
    await page.waitForTimeout(2500);
    await readerToolbar(page, 'Chapters');
    await shot(page, 'readers/epub-reader', 'epub-reader-toc', {hover: true});
    await page.getByText(/^chapter i\.?$/i).first().click(); // also closes the contents panel
    await page.waitForTimeout(1500);
    // The Gutenberg contents link for Chapter I lands on the front matter, so jump into the story by position.
    await readerToolbar(page, 'Location', 'bottom');
    await page.locator('.location-popover input[type=number]').fill('10');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(2500);
    await readerToolbar(page, 'Location', 'bottom'); // the popover toggles; Escape doesn't close it
    await page.mouse.move(VIEWPORT.width / 2, VIEWPORT.height / 2);
    await page.waitForTimeout(3500); // let the toolbars fade for a clean page
    await shot(page, 'readers/epub-reader', 'epub-reader-overview', {hover: true});

    await readerToolbar(page, 'Location', 'bottom');
    await shot(page, 'readers/epub-reader', 'epub-reader-navigation', {hover: true});
    await readerToolbar(page, 'Location', 'bottom');

    // A highlight, and a note on another passage, for the notes panel and the Notebook.
    // Select by clicking where text is on screen: the reader's iframe holds the whole section laid out
    // as side-by-side pages, so element positions inside it don't say what's visible.
    await bookFrame(page);
    const selectParagraph = async which => {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(500);
      // Two pages side by side; these points fall inside paragraphs on the left-hand page.
      await page.mouse.click(380, [290, 470][which], {clickCount: 3});
      await page.waitForTimeout(800);
    };
    await selectParagraph(0);
    await page.locator('.text-selection-popup .annotation-container .action-btn').click();
    await page.locator('.text-selection-popup .color-btn').first().click();
    await page.waitForTimeout(1000);
    await selectParagraph(1);
    await page.getByTitle('Add Note', {exact: true}).first().click();
    await page.waitForTimeout(800);
    await page.locator('textarea:visible').first().fill('Mrs. Bennet has already decided the new tenant will marry one of her daughters.');
    await page.locator('button:visible', {hasText: /^save/i}).first().click();
    await page.waitForTimeout(1000);
    await readerToolbar(page, 'Notes');
    await shot(page, 'readers/epub-reader', 'epub-reader-notes', {hover: true});
    await page.mouse.click(300, VIEWPORT.height / 2); // the dimmed page behind the notes drawer closes it
    await page.waitForTimeout(800);

    await readerToolbar(page, 'Settings');
    await shot(page, 'readers/epub-reader', 'epub-reader-quick-settings', {hover: true});
    await page.locator('.more-settings-btn').click();
    await page.waitForTimeout(1000);
    await shot(page, 'readers/epub-reader', 'epub-reader-theme', {hover: true});
    for (const [tab, name] of [['Typography', 'epub-reader-typography'], ['Layout', 'epub-reader-layout']]) {
      await page.getByText(tab, {exact: true}).first().click();
      await page.waitForTimeout(800);
      await shot(page, 'readers/epub-reader', name, {hover: true});
    }
    await page.keyboard.press('Escape');
  },
});

Object.assign(scenes, {
  // The PDF reader (pdf.js): a page of The Art of War, and the tools menu with its view modes.
  async pdfReader(page) {
    const id = await bookId(page, 'The Art of War');
    await open(page, `/pdf-reader/book/${id}`);
    await page.waitForTimeout(3000);
    for (let i = 0; i < 3; i++) {
      await page.getByTitle('Next Page', {exact: true}).first().click();
      await page.waitForTimeout(500);
    }
    await page.waitForTimeout(1000);
    await shot(page, 'readers/pdf-reader', 'pdf-reader-overview');
    await page.getByTitle('Tools', {exact: true}).first().click();
    await page.waitForTimeout(800);
    await shot(page, 'readers/pdf-reader', 'pdf-reader-view-modes', {hover: true});
    await page.keyboard.press('Escape');
  },

  // The comic reader: a page of Neill's Oz drawings, the page list, settings, a note and the shortcuts.
  async cbxReader(page) {
    const id = await bookId(page, 'The Marvelous Land of Oz Picture Book');
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    const notes = await (await page.request.get(`${BASE}/api/v2/book-notes/book/${id}`, {headers})).json();
    for (const note of notes) await page.request.delete(`${BASE}/api/v2/book-notes/${note.id}`, {headers});

    await open(page, `/cbx-reader/book/${id}`);
    await page.waitForTimeout(2500);
    for (let i = 0; i < 2; i++) {
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(700);
    }
    await page.mouse.move(VIEWPORT.width / 2, VIEWPORT.height / 2);
    await page.waitForTimeout(3500); // controls fade while reading
    await shot(page, 'readers/cbx-reader', 'cbx-reader-overview', {hover: true});

    // Drawers sit over a dimmed page and close when it's clicked (not with Escape); dialogs close with Escape.
    const toggle = async (title, name, closeAt) => {
      await readerToolbar(page, title);
      await shot(page, 'readers/cbx-reader', name, {hover: true});
      if (closeAt) await page.mouse.click(closeAt, VIEWPORT.height / 2);
      else await page.keyboard.press('Escape');
      await page.waitForTimeout(800);
    };
    await toggle('Contents', 'cbx-reader-pages', VIEWPORT.width - 150); // drawer on the left
    await toggle('Settings', 'cbx-reader-settings', 150); // drawer on the right
    await toggle('Keyboard Shortcuts (?)', 'cbx-reader-shortcuts');

    await readerToolbar(page, 'Add Note');
    await page.locator('textarea:visible').first().fill('Jack Pumpkinhead comes to life on this page - compare the drawing with the description in chapter 2.');
    await shot(page, 'readers/cbx-reader', 'cbx-reader-notes', {hover: true});
    await page.keyboard.press('Escape');
  },
});

// Settings screenshots: [tab, section heading or null for the top of the tab, help page, image name,
// optionally the heading's selector].
const SETTINGS_SHOTS = [
  ['reader', 'Settings Application Mode', 'reader-preferences', 'settings-application-mode'],
  ['reader', 'Appearance', 'reader-preferences', 'ebook-appearance'],
  ['reader', 'Typography', 'reader-preferences', 'ebook-typography'],
  ['reader', 'Comic Book Reader: Default Settings', 'reader-preferences', 'comic-reader'],
  ['view', 'Library and Shelf View & Sort Preferences', 'view-preferences', 'library-shelf-view-sort'],
  ['view', 'Filter Preferences', 'view-preferences', 'filter-preferences'],
  ['view', 'Sidebar Library and Shelf Sorting Preference', 'view-preferences', 'sidebar-sorting'],
  ['metadata', 'Metadata Persistence', 'metadata/metadata-settings', 'metadata-persistence'],
  ['metadata', 'Automatic Metadata Download', 'metadata/metadata-settings', 'auto-metadata-download'],
  ['metadata', 'Metadata Providers', 'metadata/metadata-settings', 'metadata-providers'],
  ['metadata', 'Enabled Fields in Metadata Editor & Picker', 'metadata/metadata-settings', 'enabled-fields'],
  ['metadata', 'Public Reviews', 'metadata/metadata-settings', 'public-reviews'],
  ['metadata-library', null, 'metadata/metadata-fetch-configuration', 'metadata-configuration'],
  ['naming-pattern', null, 'metadata/file-naming-patterns', 'file-naming-patterns'],
  ['application', null, 'tools/global-preferences', 'global-preferences'],
  ['user', null, 'tools/user-management', 'user-management'],
  ['task', null, 'tools/task-manager', 'system-task-manager'],
  ['audit-logs', null, 'tools/audit-logs', 'audit-logs'],
  ['device', 'KOReader Sync Configuration', 'tools/devices', 'koreader-sync'],
  ['device', 'Hardcover Integration', 'tools/devices', 'hardcover-integration'],
  ['device', 'Kobo Integration Configuration', 'tools/devices', 'kobo-integration'],
  ['device', 'Administrator Settings', 'tools/devices', 'kobo-admin-settings'],
  ['reader', 'Custom Font Library', 'tools/custom-fonts', 'font-00'],
  // Sections the old site showed as small inline pictures.
  ['reader', 'Layout', 'reader-preferences', 'ebook-layout'],
  ['reader', 'PDF Reader: Default Settings', 'reader-preferences', 'pdf-reader-settings'],
  ['reader', 'Custom Font Library', 'reader-preferences', 'custom-font-library'],
  ['view', 'View Preferences', 'view-preferences', 'view-preferences-section', 'h3'], // not the page title (h2)
  ['view', 'Layout', 'view-preferences', 'layout'],
  ['metadata', 'Sidecar JSON Files', 'metadata/metadata-settings', 'sidecar-json-files'],
];

Object.assign(scenes, {
  async settings(page) {
    let currentTab = null;
    const only = process.env.TROVE_DOCS_SETTINGS_ONLY?.split(',');
    for (const [tab, heading, pageSlug, name, selector = 'h2, h3, h4, .section-title'] of SETTINGS_SHOTS) {
      if (only && !only.includes(name)) continue;
      if (tab !== currentTab || !heading) {
        await open(page, `/settings?tab=${tab}`);
        await page.waitForTimeout(1200);
        currentTab = tab;
      }
      if (heading) await scrollToHeading(page, heading, selector);
      await shot(page, pageSlug, name);
    }
  },
});

Object.assign(scenes, {
  // The library's menu and what it opens: edit, find duplicates, add a physical book, re-scan.
  async libraryMenu(page) {
    const library = (await apiGet(page, '/libraries')).find(l => l.name === 'Classics');
    const menu = async item => {
      await page.locator('.entity-menu-wrapper').locator('button, a, i').first().click();
      await page.waitForTimeout(700);
      if (item) {
        await page.locator('[role=menuitem], .p-menu li, .p-tieredmenu li', {hasText: item}).first().click();
        await page.waitForTimeout(1200);
      }
    };
    await open(page, `/library/${library.id}/books`);
    await menu();
    await shot(page, 'library/duplicate-detection', 'library-menu');
    await shot(page, 'library/physical-books', 'library-menu');
    await page.keyboard.press('Escape');

    await menu('Edit Library');
    await shot(page, 'library/edit-library', 'edit-library-1');
    const body = page.locator('.library-creator .dialog-body').first();
    await body.evaluate(el => el.scrollBy(0, 420));
    await page.waitForTimeout(500);
    await shot(page, 'library/edit-library', 'edit-library-2');
    await body.evaluate(el => el.scrollTo(0, el.scrollHeight));
    await page.waitForTimeout(500);
    await shot(page, 'library/edit-library', 'edit-library-3');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(600);

    await menu('Find Duplicates');
    await shot(page, 'library/duplicate-detection', 'find-duplicates-dialog');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(600);

    await menu('Add Physical Book');
    const dialog = page.locator('.p-dialog').last();
    const fields = dialog.locator('input[type=text]:visible, input:not([type]):visible');
    await fields.nth(0).fill('Sense and Sensibility');
    await shot(page, 'library/physical-books', 'add-dialog');
    await page.keyboard.press('Escape'); // not added: the library stays as it is
    await page.waitForTimeout(600);

    // Re-scanning shows the same progress as the first scan after creating a library.
    await menu('Re-scan Library');
    await page.locator('.p-dialog button', {hasText: /^\s*Rescan\s*$/}).click();
    // The activity button's icon changes with state (pulse, spinner, bell), so find it by its aria label.
    await page.locator('button.topbar-item:has(i[aria-label])').first().click();
    await page.waitForTimeout(600);
    await shot(page, 'library/setup-first-library', 'library-processing', {keepToasts: true});
  },
});

/** PUTs a JSON body to an API path as the signed-in user; throws on an error response. */
async function apiPut(page, apiPath, body) {
  const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
  const response = await page.request.put(`${BASE}/api/v1${apiPath}`, {headers: {Authorization: `Bearer ${token}`}, data: body});
  if (!response.ok()) throw new Error(`PUT ${apiPath} failed: HTTP ${response.status()}`);
  return response.status() === 204 ? null : response.json().catch(() => null);
}

// Sample tags, some deliberately untidy ("Sci-Fi", "SciFi", "Science-Fiction"; "Mystery & Detective")
// so the Metadata Manager has something to merge and split.
const SAMPLE_TAGS = {
  'The Time Machine': ['Sci-Fi', 'Victorian'],
  'The War of the Worlds': ['Science-Fiction', 'Victorian', 'Aliens'],
  'Twenty Thousand Leagues Under the Sea': ['SciFi', 'Ocean'],
  'Frankenstein': ['Sci-Fi', 'Monsters'],
  'Dracula': ['Monsters', 'Vampires', 'Epistolary'],
  'The Hound of the Baskervilles': ['Victorian', 'Mystery & Detective'],
  'A Study in Scarlet': ['Victorian', 'Mystery & Detective'],
  'The Sign of the Four': ['Victorian', 'Mystery & Detective'],
  'Treasure Island': ['Pirates', 'Ocean'],
  'Moby-Dick': ['Ocean', 'Whaling'],
  'Around the World in Eighty Days': ['Travel', 'Victorian'],
  'Pride and Prejudice': ['Regency'],
  'Emma': ['Regency'],
  'The Picture of Dorian Gray': ['Victorian'],
};

/** Puts the sample tags back on their books (and removes any others). */
async function resetSampleTags(page) {
  // withDescription: the metadata is sent back whole, and a list without descriptions would erase them.
  for (const book of await apiGet(page, '/books?withDescription=true')) {
    const tags = SAMPLE_TAGS[book.metadata?.title] || [];
    if (JSON.stringify([...(book.metadata.tags || [])].sort()) === JSON.stringify([...tags].sort())) continue;
    await apiPut(page, `/books/${book.id}/metadata`, {metadata: {...book.metadata, tags}, clearFlags: {}});
  }
}

Object.assign(scenes, {
  // The Metadata Manager: merging tag variants into one, splitting a combined tag, deleting a tag.
  async metadataManager(page) {
    await resetSampleTags(page);
    await open(page, '/dashboard');
    await page.locator('.topbar-item:has(.pi-sparkles)').first().hover();
    await page.waitForTimeout(900);
    await shot(page, 'metadata/metadata-manager', 'access-settings', {hover: true});

    await open(page, '/metadata-manager');
    await shot(page, 'metadata/metadata-manager', 'overview');
    await page.locator('p-tab', {hasText: 'Tags'}).click();
    await page.waitForTimeout(800);
    const panel = page.locator('p-tabpanel:visible, .p-tabpanel:visible').first();
    const row = value => panel.locator('tr', {has: page.locator('.clickable-cell', {hasText: new RegExp(`^\\s*${value.replace(/[&-]/g, '\\$&')}\\s*$`)})});
    const dialog = () => page.locator('.p-dialog:visible').last();

    // Searching narrows the table to the entries in question (it scrolls inside a short panel otherwise).
    const search = async text => {
      await panel.locator('.search-input input').fill(text);
      await page.waitForTimeout(700);
    };
    await search('sci');
    for (const value of ['Sci-Fi', 'SciFi', 'Science-Fiction']) await row(value).locator('p-checkbox').click();
    await page.waitForTimeout(500);
    await shot(page, 'metadata/metadata-manager', 'select-items');
    await panel.locator('p-button', {hasText: 'Merge'}).click();
    await page.waitForTimeout(800);
    await dialog().locator('input').first().fill('Science Fiction');
    await shot(page, 'metadata/metadata-manager', 'merge-target');
    await dialog().locator('p-button', {hasText: 'Confirm'}).click();
    await page.waitForTimeout(2500);

    await search('mystery');
    const mystery = row('Mystery & Detective');
    await mystery.locator('p-button:has(.pi-pencil)').hover();
    await page.waitForTimeout(900);
    await shot(page, 'metadata/metadata-manager', 'rename-button', {hover: true});
    await mystery.locator('p-button:has(.pi-pencil)').click();
    await page.waitForTimeout(800);
    await dialog().locator('#renameTarget, input').first().fill('Mystery, Detective');
    await shot(page, 'metadata/metadata-manager', 'rename-input');
    await dialog().locator('p-button', {hasText: 'Confirm'}).click();
    await page.waitForTimeout(2500);
    await search('');
    await shot(page, 'metadata/metadata-manager', 'rename-confirm', {keepToasts: true});

    await search('');
    await row('Whaling').locator('p-button:has(.pi-trash)').click();
    await page.waitForTimeout(800);
    await shot(page, 'metadata/metadata-manager', 'delete-option');
    await dialog().locator('p-button', {hasText: 'Cancel'}).click();

    await resetSampleTags(page); // leave the untidy tags for the next run
  },
});

// Public-domain EPUBs dropped into the Bookdrop folder (from Project Gutenberg, under the file names a
// reader might have), and the titles they import as, so a run can remove what the last one imported.
const BOOKDROP_SOURCE = process.env.TROVE_DOCS_BOOKDROP_SOURCE || '/srv/trove/docs-staging';
const BOOKDROP_FOLDER = process.env.TROVE_DOCS_BOOKDROP || '/srv/trove-docs/bookdrop';
const BOOKDROP_FILES = {
  'pg113.epub': 'Frances Hodgson Burnett - The Secret Garden.epub',
  'pg289.epub': 'Kenneth Grahame - The Wind in the Willows.epub',
  'pg219.epub': 'Joseph Conrad - Heart of Darkness.epub',
};
const BOOKDROP_TITLES = [/Secret Garden/i, /Wind in the Willows/i, /Heart of Darkness/i];

async function apiSend(page, method, apiPath, body) {
  const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
  const response = await page.request.fetch(`${BASE}/api/v1${apiPath}`, {method, headers: {Authorization: `Bearer ${token}`}, data: body});
  if (!response.ok()) throw new Error(`${method} ${apiPath} failed: HTTP ${response.status()}`);
}

/**
 * Blurs what the metadata providers returned on the Bookdrop review screen - fetched covers and long
 * fetched text such as descriptions - leaving the books' own embedded covers and metadata sharp.
 */
async function blurBookdropFetched(page) {
  await page.evaluate(() => {
    document.querySelectorAll('img[alt="Fetched Cover"]').forEach(img => { img.style.filter = 'blur(10px)'; });
    document.querySelectorAll('.thumbnail-row').forEach(row => {
      const fetched = row.querySelectorAll('.thumbnail-column')[1];
      fetched?.querySelectorAll('img').forEach(img => { img.style.filter = 'blur(10px)'; });
    });
    document.querySelectorAll('.src').forEach(el => {
      const text = el.value ?? el.textContent ?? '';
      if (text.trim().length > 90) el.style.filter = 'blur(4px)';
    });
  });
}

/** Clears the Bookdrop queue and deletes books (and their authors) an earlier run imported from it. */
async function resetBookdrop(page) {
  await apiSend(page, 'POST', '/bookdrop/files/discard', {selectAll: true, excludedIds: [], selectedIds: []});
  const imported = (await apiGet(page, '/books?stripForListView=true'))
    .filter(b => BOOKDROP_TITLES.some(t => t.test(b.metadata?.title || '')));
  if (imported.length) await apiSend(page, 'DELETE', `/books?ids=${imported.map(b => b.id).join(',')}`);
  // Deleting a book leaves its author behind; remove authors with no books left.
  const orphans = (await apiGet(page, '/authors')).filter(a => a.bookCount === 0).map(a => a.id);
  if (orphans.length) await apiSend(page, 'DELETE', '/authors', orphans);
}

Object.assign(scenes, {
  // Bookdrop: files dropped into the folder, the progress in the activity panel, the review screen with
  // a book's metadata comparison (provider results blurred), and the import summary. The imported books
  // are deleted again afterwards, leaving the sample library as it was.
  async bookdrop(page) {
    const {copyFileSync} = await import('node:fs');
    await resetBookdrop(page);
    const booksBefore = await bookCount(page);
    await open(page, '/dashboard');
    for (const [source, name] of Object.entries(BOOKDROP_FILES)) {
      copyFileSync(path.join(BOOKDROP_SOURCE, source), path.join(BOOKDROP_FOLDER, name));
    }
    const activity = page.locator('button.topbar-item:has(i[aria-label])').first();
    await page.waitForTimeout(2500);
    await activity.click();
    await page.waitForTimeout(600);
    await shot(page, 'bookdrop', 'processing-status');
    await page.keyboard.press('Escape');

    // Wait for the files to be processed (metadata is fetched from the providers for each).
    for (let i = 0; i < 60; i++) {
      const summary = await apiGet(page, '/bookdrop/notification');
      if (summary.pendingCount >= Object.keys(BOOKDROP_FILES).length) break;
      await page.waitForTimeout(2000);
    }
    await open(page, '/dashboard');
    await activity.click();
    await page.waitForTimeout(800);
    await shot(page, 'bookdrop', 'bookdrop-progress');
    await page.getByRole('button', {name: 'Review'}).first().click();
    await page.waitForTimeout(2500);
    await page.mouse.click(700, 600); // close the activity panel, which stays open across the navigation
    await page.waitForTimeout(500);
    // Every book goes to the Classics library, in its only folder (the defaults apply to selected books).
    await page.locator('.footer p-button', {hasText: /Select\s+All/}).click();
    const defaults = page.locator('.default-controls');
    for (const [index, option] of [[0, 'Classics'], [1, /books/]]) {
      await defaults.locator('p-select').nth(index).click();
      await page.locator('.p-select-overlay li, .p-select-option', {hasText: option}).first().click();
      await page.waitForTimeout(400);
    }
    await defaults.locator('p-button').click();
    await page.waitForTimeout(800);
    await blurBookdropFetched(page);
    await shot(page, 'bookdrop', 'bookdrop-pre');

    // Open the comparison for a book the providers found (lookups can fail, so check which did).
    const files = (await apiGet(page, '/bookdrop/files?status=PENDING_REVIEW&size=50')).content;
    const matched = files.find(f => f.fetchedMetadata?.title) || files[0];
    const compared = page.locator('.file-item', {hasText: matched.fileName}).first();
    await compared.locator('.file-row p-button').last().click();
    await page.waitForTimeout(1500);
    await blurBookdropFetched(page);
    await shot(page, 'bookdrop', 'bookdrop-dropdown');
    await compared.locator('.file-row p-button').last().click();
    await page.waitForTimeout(600);

    await page.locator('.footer p-button', {hasText: 'Finalize'}).click();
    await page.waitForTimeout(1000);
    await page.getByRole('alertdialog').or(page.getByRole('dialog')).last().getByRole('button', {name: 'Finalize'}).click();
    await page.waitForSelector('text=Import Summary', {timeout: 60000});
    await page.waitForTimeout(1000);
    await shot(page, 'bookdrop', 'bookdrop-summary');

    await resetBookdrop(page);
    if (await bookCount(page) !== booksBefore) throw new Error('The Bookdrop scene left the library with a different number of books');
  },
});

Object.assign(scenes, {
  // Searching the metadata providers for a book, and comparing a result with the book's own metadata.
  // Everything the providers return is blurred; nothing is saved.
  async metadataSearch(page) {
    const id = await bookId(page, 'The Time Machine');
    await open(page, `/book/${id}`);
    await page.locator('p-tab[value=match]').click();
    await page.waitForTimeout(1000);
    const searchButton = page.locator('.search-button-field button').first();
    if (await searchButton.isEnabled()) await searchButton.click();
    // Providers answer in their own time; wait until none is still searching.
    await page.waitForSelector('.metadata-card', {timeout: 60000});
    for (let i = 0; i < 30 && await page.locator('.fetching-badge').count(); i++) await page.waitForTimeout(1000);
    await page.waitForTimeout(1500);
    await blurProviderContent(page, '.results-section');
    await shot(page, 'metadata/metadata-center', 'search-metadata');

    await page.locator('.metadata-card').first().click();
    // The picker opens with the search result, then loads the provider's full record.
    await page.waitForTimeout(1000);
    await page.locator('.detail-loading-banner').waitFor({state: 'detached', timeout: 60000}).catch(() => {});
    await page.waitForTimeout(1500);
    await page.evaluate(() => {
      document.querySelectorAll('.field-side.fetched img').forEach(img => { img.style.filter = 'blur(10px)'; });
      document.querySelectorAll('.field-side.fetched').forEach(side => {
        side.querySelectorAll('input, textarea, p, div, span').forEach(el => {
          const text = el.value ?? [...el.childNodes].filter(n => n.nodeType === Node.TEXT_NODE).map(n => n.textContent).join('');
          if ((text || '').trim().length > 90) el.style.filter = 'blur(4px)';
        });
      });
    });
    await shot(page, 'metadata/metadata-center', 'search-metadata-compare');
  },

  // The Files and Notes tabs on a book's page.
  async bookTabs(page) {
    const id = await bookId(page, 'Pride and Prejudice');
    await open(page, `/book/${id}`);
    for (const [tab, name] of [['Files', 'files-tab'], ['Notes', 'notes-tab']]) {
      await page.getByText(tab, {exact: true}).first().click();
      await page.waitForTimeout(1200);
      await scrollToHeading(page, tab, '.p-tab-active, [role=tab][aria-selected=true]');
      await shot(page, 'metadata/metadata-center', name);
    }
  },

  // The Notebook: every highlight and note across the library.
  async notebook(page) {
    await open(page, '/notebook');
    await page.waitForTimeout(1500);
    await shot(page, 'notebook', 'notebook-overview');
  },
});

Object.assign(scenes, {
  // Email: adding an SMTP provider (example values only - it is never used to send anything), adding
  // recipients, and the Custom Send dialog on a book.
  async email(page) {
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    for (const kind of ['providers', 'recipients']) {
      for (const item of await apiGet(page, `/email/${kind}`)) {
        await page.request.delete(`${BASE}/api/v1/email/${kind}/${item.id}`, {headers});
      }
    }
    await open(page, '/settings?tab=email-v2');
    await page.waitForTimeout(1000);
    await shot(page, 'email-setup', 'email-1');

    await page.getByRole('button', {name: 'Add Provider'}).click();
    await page.waitForTimeout(800);
    const provider = page.locator('.p-dialog:visible, .p-dynamicdialog:visible').last();
    await provider.locator('#providerName').fill('Gmail');
    await provider.locator('#host').fill('smtp.gmail.com');
    await provider.locator('#port').fill('587');
    await provider.locator('#username').fill('elinor@example.com');
    await provider.locator('#password input, input#password').first().fill('example-app-password');
    await provider.locator('#fromAddress').fill('elinor@example.com');
    for (const id of ['auth', 'startTls']) {
      if (!await provider.locator(`#${id}`).isChecked()) await provider.locator(`p-checkbox:has(#${id})`).click();
    }
    await shot(page, 'email-setup', 'email-2');
    await provider.locator('p-button').last().click();
    await page.waitForTimeout(1200);

    const addRecipient = async (name, email) => {
      await page.getByRole('button', {name: 'Add Recipient'}).click();
      await page.waitForTimeout(800);
      const dialog = page.locator('.p-dialog:visible, .p-dynamicdialog:visible').last();
      await dialog.locator('#recipientName').fill(name);
      await dialog.locator('#email').fill(email);
      return dialog;
    };
    await addRecipient('Marianne', 'marianne@example.com').then(d => d.locator('p-button').last().click());
    await page.waitForTimeout(1000);
    const kindle = await addRecipient("Elinor's Kindle", 'elinor-kindle@example.com');
    await shot(page, 'email-setup', 'email-3');
    await kindle.locator('p-button').last().click();
    await page.waitForTimeout(1200);
    await scrollBy(page, 2000); // the providers and recipients both fit once scrolled down
    await shot(page, 'email-setup', 'email-4');

    await open(page, '/all-books');
    const card = page.locator('app-book-card', {hasText: 'Emma'}).first();
    await card.getByRole('button', {name: 'Book actions menu'}).click();
    await page.waitForTimeout(600);
    // The submenu doesn't show for a scripted hover, but the menu's keyboard navigation works.
    await page.locator('[role=menuitem]', {hasText: 'Email Book'}).first().click();
    await page.keyboard.press('ArrowRight'); // into the submenu: Quick Send
    await page.keyboard.press('ArrowDown'); // Custom Send
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1200);
    const send = page.locator('.p-dialog:visible, .p-dynamicdialog:visible').last();
    for (const [index, option] of [[0, 'Gmail'], [1, "Elinor's Kindle"]]) {
      await send.locator('p-select').nth(index).click();
      await page.locator('.p-select-option, .p-select-overlay li', {hasText: option}).first().click();
      await page.waitForTimeout(400);
    }
    await send.locator('.p-radiobutton, p-radiobutton').first().click().catch(() => {});
    await shot(page, 'email-setup', 'email-5');
    await page.keyboard.press('Escape');
  },
});

// Simple line icons drawn for these screenshots (24x24, stroked in the current colour).
const svg = body => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
const SAMPLE_ICONS = {
  'book-stack': svg('<rect x="4" y="4" width="16" height="4" rx="1"/><rect x="3" y="10" width="18" height="4" rx="1"/><rect x="5" y="16" width="14" height="4" rx="1"/>'),
  'anchor': svg('<circle cx="12" cy="5" r="2"/><path d="M12 7v14"/><path d="M8 11h8"/><path d="M4 14a8 8 0 0 0 16 0"/>'),
  'crescent-moon': svg('<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>'),
  'magnifier': svg('<circle cx="10" cy="10" r="6"/><path d="M14.5 14.5L20 20"/>'),
  'lantern': svg('<path d="M9 3h6"/><path d="M12 3v2"/><rect x="7" y="5" width="10" height="13" rx="2"/><path d="M12 9v5"/><path d="M8 21h8"/>'),
  'castle': svg('<path d="M4 21V9h3V6h3v3h4V6h3v3h3v12z"/><path d="M10 21v-5h4v5"/>'),
};
const ICON_ADDED_IN_UI = 'castle';

Object.assign(scenes, {
  // The icon picker, from the library dialog: built-in icons, your SVG icons, and adding one.
  async customIcons(page) {
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    for (const name of Object.keys(SAMPLE_ICONS)) {
      await page.request.delete(`${BASE}/api/v1/icons/${name}`, {headers});
    }
    const preloaded = Object.entries(SAMPLE_ICONS).filter(([name]) => name !== ICON_ADDED_IN_UI);
    const saved = await page.request.post(`${BASE}/api/v1/icons/batch`, {
      headers, data: {icons: preloaded.map(([svgName, svgData]) => ({svgName, svgData}))},
    });
    if (!saved.ok()) throw new Error(`Couldn't add the sample icons: HTTP ${saved.status()}`);

    const library = (await apiGet(page, '/libraries')).find(l => l.name === 'Classics');
    await open(page, `/library/${library.id}/books`);
    await page.locator('.entity-menu-wrapper').locator('button, a, i').first().click();
    await page.waitForTimeout(700);
    await page.locator('[role=menuitem]', {hasText: 'Edit Library'}).first().click();
    await page.waitForTimeout(1200);
    await page.locator('.icon-picker-trigger').hover();
    await shot(page, 'tools/custom-icons', '0-icons--library-creator', {hover: true});

    await page.locator('.icon-picker-trigger').click();
    await page.waitForSelector('.icon-picker');
    await page.waitForTimeout(800);
    await shot(page, 'tools/custom-icons', '1-icons--prime');
    await page.locator('.icon-picker p-tab[value="1"]').click();
    await page.waitForTimeout(1200);
    await shot(page, 'tools/custom-icons', '2-icons--custom');
    await page.locator('.icon-picker p-tab[value="2"]').click();
    await page.waitForTimeout(800);
    const add = page.locator('.icon-picker p-tabpanel[value="2"]');
    await add.locator('input.input-full').fill(ICON_ADDED_IN_UI);
    await add.locator('textarea.code-textarea').fill(SAMPLE_ICONS[ICON_ADDED_IN_UI]);
    await page.waitForTimeout(800);
    await shot(page, 'tools/custom-icons', '3-icons--add-custom');
    await page.keyboard.press('Escape'); // not saved: the library keeps its icon
  },
});

// An OFL-licensed reading typeface (Literata, from Google Fonts) for the custom fonts screenshots.
const SAMPLE_FONT = process.env.TROVE_DOCS_FONT || '/srv/trove/docs-staging/fonts/Literata.ttf';

Object.assign(scenes, {
  // Uploading a font, the font collection, and choosing the font as the default and in the reader.
  async customFonts(page) {
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    for (const font of await apiGet(page, '/custom-fonts')) {
      await page.request.delete(`${BASE}/api/v1/custom-fonts/${font.id}`, {headers});
    }
    await open(page, '/settings?tab=reader');
    await scrollToHeading(page, 'Custom Font Library', 'h2, h3, h4, .section-title');
    await page.locator('.float-top-right p-button').click();
    await page.waitForSelector('.font-upload-dialog');
    await page.waitForTimeout(600);
    await shot(page, 'tools/custom-fonts', 'font-01');
    await page.locator('.font-upload-dialog input[type=file]').setInputFiles(SAMPLE_FONT);
    await page.waitForTimeout(1500);
    await page.locator('#fontName').fill('Literata');
    await shot(page, 'tools/custom-fonts', 'font-02');
    await page.locator('.font-upload-dialog .dialog-footer p-button').last().click();
    await page.waitForTimeout(2500);
    await scrollToHeading(page, 'Custom Font Library', 'h2, h3, h4, .section-title');
    await shot(page, 'tools/custom-fonts', 'font-03');

    // The default font for EPUBs, in the reader settings.
    await open(page, '/settings?tab=reader');
    await scrollToHeading(page, 'Typography', 'h2, h3, h4, .section-title');
    // Uploaded fonts join the Font Family choices (shown, not chosen: the default stays as it is).
    await page.getByText('Literata', {exact: true}).first().hover();
    await page.waitForTimeout(500);
    await shot(page, 'tools/custom-fonts', 'font-04', {hover: true});

    // And for one book, from the reader's own settings.
    const id = await bookId(page, 'Emma');
    await open(page, `/ebook-reader/book/${id}`);
    await page.waitForTimeout(2500);
    await readerToolbar(page, 'Location', 'bottom'); // into the story, so there's text in the new font
    await page.locator('.location-popover input[type=number]').fill('20');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(2500);
    await readerToolbar(page, 'Location', 'bottom');
    await readerToolbar(page, 'Settings');
    await page.locator('.more-settings-btn').click();
    await page.waitForTimeout(1000);
    await page.getByText('Typography', {exact: true}).first().click();
    await page.waitForTimeout(800);
    const literata = page.getByText('Literata', {exact: true}).first();
    if (await literata.count()) await literata.click();
    await page.waitForTimeout(1500);
    await shot(page, 'tools/custom-fonts', 'font-05', {hover: true});
  },
});

// A second, non-admin account (another public-domain persona) for the user permission screenshots.
const SECOND_USER = {username: 'marianne', name: 'Marianne Dashwood', email: 'marianne@example.com'};
// Example KOReader sync credentials (the docs instance only).
const KOREADER = {username: 'elinor', password: 'example-sync-password'};
// Books near the top of the library (the grid only draws the cards in view), and one it reports progress in.
const KOBO_SHELF_BOOKS = ['Dracula', 'Emma', 'Frankenstein', 'Jane Eyre'];

Object.assign(scenes, {
  // Trove's side of KOReader and Kobo sync: permissions, settings, the Kobo shelf, and synced progress.
  // The progress shown was sent through Trove's own KOReader and Kobo sync endpoints, as a device would.
  async devices(page) {
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    const library = (await apiGet(page, '/libraries')).find(l => l.name === 'Classics');
    if (!(await apiGet(page, '/users')).some(u => u.username === SECOND_USER.username)) {
      const response = await page.request.post(`${BASE}/api/v1/auth/register`, {headers, data: {
        ...SECOND_USER, password: `${PASSWORD}-${Date.now()}`, selectedLibraries: [library.id],
        permissionDownload: true, permissionEmailBook: true, permissionAccessOpds: true,
        permissionSyncKoreader: true, permissionSyncKobo: true, permissionAccessUserStats: true,
      }});
      if (!response.ok()) throw new Error(`Couldn't create ${SECOND_USER.username}: HTTP ${response.status()}`);
    }

    // Permissions, on the Users tab.
    await open(page, '/settings?tab=user');
    await page.locator('.user-card', {hasText: SECOND_USER.username}).locator('.expand-btn').click();
    await page.waitForTimeout(1000);
    await page.locator('label', {hasText: 'KOReader Sync'}).first().evaluate(el => el.scrollIntoView({block: 'center'}));
    await page.waitForTimeout(600);
    await shot(page, 'integration/koreader', 'user-permissions');
    await shot(page, 'integration/kobo', 'user-permissions');

    // KOReader: the settings before and while entering credentials, then sync switched on.
    await open(page, '/settings?tab=device');
    const koreader = page.locator('app-koreader-settings-component');
    await shot(page, 'integration/koreader', 'device-settings');
    const editSave = koreader.locator('.setting-item', {hasText: 'Settings Management'}).locator('p-button');
    if ((await editSave.innerText()).includes('Edit')) await editSave.click();
    await page.waitForTimeout(500);
    await koreader.locator('#username').fill(KOREADER.username);
    await koreader.locator('#password input, input#password').first().fill(KOREADER.password);
    await shot(page, 'integration/koreader', 'credentials-setup');
    await editSave.click();
    await page.waitForTimeout(1200);
    const koreaderToggle = koreader.locator('p-toggle-switch[name=syncEnabled]');
    if (!await koreaderToggle.locator('input').isChecked()) await koreaderToggle.click();
    await page.waitForTimeout(1000);

    // A KOReader device reports its position in Treasure Island (books are matched by file hash).
    const treasure = await apiGet(page, `/books/${await bookId(page, 'Treasure Island')}`);
    const {createHash} = await import('node:crypto');
    const sync = await page.request.put(`${BASE}/api/koreader/syncs/progress`, {
      headers: {'x-auth-user': KOREADER.username, 'x-auth-key': createHash('md5').update(KOREADER.password).digest('hex')},
      data: {document: treasure.primaryFile.currentHash, percentage: 0.4172, progress: '/body/DocFragment[14]/body/div/p[12]/text().0',
        device: 'KOReader', device_id: 'docs-sample-device'},
    });
    if (!sync.ok()) throw new Error(`KOReader progress sync failed: HTTP ${sync.status()}`);

    // Kobo: sync switched on, which gives the device its address.
    const kobo = page.locator('app-kobo-sync-setting-component');
    const koboToggle = kobo.locator('p-toggle-switch#syncEnabled');
    if (!await koboToggle.locator('input').isChecked()) await koboToggle.click();
    await page.waitForTimeout(1500);
    await kobo.evaluate(el => el.scrollIntoView({block: 'start'}));
    await scrollBy(page, -80);
    await shot(page, 'integration/kobo', 'device-settings');
    const koboToken = (await kobo.locator('#koboToken').inputValue()).split('/').filter(Boolean).pop();

    // Books go onto the Kobo shelf; start from an empty shelf so the scene can be run again.
    const koboShelf = (await apiGet(page, '/shelves')).find(s => s.name === 'Kobo');
    const allBooks = await apiGet(page, '/books?stripForListView=true');
    await page.request.post(`${BASE}/api/v1/books/shelves`, {headers, data: {
      bookIds: allBooks.map(b => b.id), shelvesToAssign: [], shelvesToUnassign: [koboShelf.id]}});
    // The first book through the book menu, as the help describes; the others through the API.
    const [first, ...others] = KOBO_SHELF_BOOKS;
    const otherIds = [];
    for (const title of others) otherIds.push(await bookId(page, title));
    await page.request.post(`${BASE}/api/v1/books/shelves`, {headers, data: {
      bookIds: otherIds, shelvesToAssign: [koboShelf.id], shelvesToUnassign: []}});
    await open(page, '/all-books');
    await page.locator('app-book-card', {hasText: first}).first().getByRole('button', {name: 'Book actions menu'}).click();
    await page.waitForTimeout(600);
    await page.locator('[role=menuitem]', {hasText: 'Assign Shelf'}).first().click();
    await page.waitForTimeout(1000);
    const dialog = page.locator('.p-dialog').last();
    await dialog.getByText('Kobo', {exact: true}).first().click();
    await shot(page, 'integration/kobo', 'assign-shelf');
    await dialog.getByRole('button', {name: 'Save Changes'}).click();
    await page.waitForTimeout(1200);
    await open(page, `/shelf/${koboShelf.id}/books`);
    await shot(page, 'integration/kobo', 'shelf-with-books');

    // A Kobo reports its position in Frankenstein.
    const koboBook = await bookId(page, 'Frankenstein');
    const now = new Date().toISOString();
    const state = await page.request.put(`${BASE}/api/kobo/${koboToken}/v1/library/${koboBook}/state`, {data: {ReadingStates: [{
      EntitlementId: String(koboBook), Created: now, LastModified: now, PriorityTimestamp: now,
      StatusInfo: {LastModified: now, Status: 'Reading', TimesStartedReading: 1},
      Statistics: {LastModified: now, SpentReadingMinutes: 48, RemainingTimeMinutes: 71},
      CurrentBookmark: {LastModified: now, ProgressPercent: 63, ContentSourceProgressPercent: 40,
        Location: {Value: 'kobo.1.1', Type: 'KoboSpan', Source: 'OEBPS/letter-4.xhtml'}},
    }]}});
    if (!state.ok()) throw new Error(`Kobo reading state sync failed: HTTP ${state.status()}`);

    // Removing a book on the Kobo takes it off the Kobo shelf at the next sync.
    await page.request.post(`${BASE}/api/v1/books/shelves`, {headers, data: {
      bookIds: [await bookId(page, 'Dracula')], shelvesToAssign: [], shelvesToUnassign: [koboShelf.id]}});
    await open(page, `/shelf/${koboShelf.id}/books`);
    await shot(page, 'integration/kobo', 'shelf-after-removal');

    await open(page, `/book/${koboBook}`);
    await shot(page, 'integration/kobo', 'synced-progress');
    await open(page, `/book/${treasure.id}`);
    await shot(page, 'integration/koreader', 'synced-progress');
  },
});

// Example OIDC provider settings (typed in for the screenshots, never saved).
const OIDC_EXAMPLES = {
  authentik: {page: 'authentication/authentik', settings: 'authentik-10', disable: 'authentik-14', home: 'authentik-13', login: 'authentik-15',
    providerName: 'Authentik', issuerUri: 'https://auth.example.com/application/o/trove/', clientId: 'hV3pQ8bT2mX7wK4nZ9cR1sY6dF0gJ5aL'},
  authelia: {page: 'authentication/authelia', settings: 'authelia-01', disable: 'authelia-03', home: 'authelia-02', login: 'authelia-04',
    providerName: 'Authelia', issuerUri: 'https://auth.example.com', clientId: 'trove'},
};

Object.assign(scenes, {
  // Trove's side of single sign-on: the OIDC settings filled in for Authentik and Authelia, the login
  // methods (where OIDC is switched off again), the dashboard after signing in, and the login page.
  async oidc(page) {
    for (const example of Object.values(OIDC_EXAMPLES)) {
      await open(page, '/settings?tab=authentication');
      await page.waitForTimeout(800);
      await shot(page, example.page, example.disable);
      await page.locator('#providerName').fill(example.providerName);
      await page.locator('#clientId').fill(example.clientId);
      await page.locator('#clientSecret input, input#clientSecret').first().fill('example-client-secret-not-real');
      await page.locator('#issuerUri').fill(example.issuerUri);
      for (const [id, claim] of [['claimUsername', 'preferred_username'], ['claimEmail', 'email'], ['claimName', 'name'], ['claimGroups', 'groups']]) {
        const field = page.locator(`#${id}`);
        if (!await field.inputValue()) await field.fill(claim);
      }
      await scrollToHeading(page, 'OIDC Provider Configuration', 'h3');
      await shot(page, example.page, example.settings);
      await open(page, '/dashboard');
      await shot(page, example.page, example.home);
    }
    // Signed out: the ordinary login page.
    await page.evaluate(() => localStorage.clear());
    await page.context().clearCookies();
    await page.goto(`${SITE}/login`, {waitUntil: 'networkidle'});
    await page.waitForTimeout(800);
    for (const example of Object.values(OIDC_EXAMPLES)) await shot(page, example.page, example.login);
  },
});

Object.assign(scenes, {
  // OPDS: the server and the Komga-compatible API switched on, their addresses, and an OPDS account.
  async opds(page) {
    await open(page, '/settings?tab=opds');
    await page.waitForTimeout(800);
    for (const item of await page.locator('.toggle-item:not(.sub-item)').all()) {
      if (!await item.locator('p-toggleswitch input').isChecked()) {
        await item.locator('p-toggleswitch').click();
        await page.waitForTimeout(1000);
      }
    }
    if (!await page.locator('text=elinor-ereader').count()) {
      await page.getByRole('button', {name: 'Add User'}).click();
      await page.waitForTimeout(800);
      const dialog = page.locator('.p-dialog:visible').last();
      await dialog.locator('#username').fill('elinor-ereader');
      await dialog.locator('#password').fill('example-opds-password');
      await dialog.locator('p-button').last().click();
      await page.waitForTimeout(1200);
    }
    await shot(page, 'integration/opds', 'opds', {fullPage: true});
  },
});

Object.assign(scenes, {
  // The audiobook player with the LibriVox recording of The Happy Prince: the player, its chapter
  // list, and the sleep timer. Nothing is played aloud; the page is only opened.
  async audiobookPlayer(page) {
    const id = await bookId(page, 'The Happy Prince and Other Tales');
    await open(page, `/audiobook-player/book/${id}`);
    await page.waitForTimeout(3000);
    await shot(page, 'readers/audiobook-player', 'audiobook-player');
    const trackList = page.locator('header p-button:has(.pi-list)').first();
    await trackList.click();
    await page.waitForTimeout(1000);
    await shot(page, 'readers/audiobook-player', 'audiobook-chapters');
    await page.locator('.track-list-sidebar .sidebar-header').locator('button, p-button').last().click(); // its close button
    await page.waitForTimeout(600);
    await page.locator('p-button', {hasText: 'Sleep Timer'}).click();
    await page.waitForTimeout(700);
    await shot(page, 'readers/audiobook-player', 'audiobook-sleep-timer');
    await page.keyboard.press('Escape');
  },
});

Object.assign(scenes, {
  // API tokens: creating one (its value blurred) and the list. Earlier runs' tokens are revoked first.
  async apiTokens(page) {
    const token = await page.evaluate(() => localStorage.getItem('accessToken_Internal'));
    const headers = {Authorization: `Bearer ${token}`};
    const existing = await (await page.request.get(`${BASE}/api/v1/api-tokens`, {headers})).json().catch(() => []);
    for (const t of Array.isArray(existing) ? existing : []) {
      await page.request.delete(`${BASE}/api/v1/api-tokens/${t.id}`, {headers});
    }
    await open(page, '/settings?tab=api-tokens');
    await page.waitForTimeout(800);
    await page.getByRole('button', {name: 'New Token'}).click();
    await page.waitForTimeout(600);
    await page.locator('.p-dialog:visible input').first().fill('Reading app on my phone');
    await page.locator('.p-dialog:visible').getByRole('button', {name: 'Create'}).click();
    await page.waitForTimeout(1200);
    await page.evaluate(() => {
      for (const el of document.querySelectorAll('.p-dialog input, .p-dialog code, .p-dialog pre')) {
        if (/blt_/.test(el.value || el.textContent || '')) el.style.filter = 'blur(5px)';
      }
    });
    await shot(page, 'tools/api-tokens', 'token-created');
    await page.locator('.p-dialog:visible').getByRole('button', {name: 'Done'}).click();
    await page.waitForTimeout(800);
    await shot(page, 'tools/api-tokens', 'api-tokens');
  },
});

/** The id of the book with this title, from the API. */
async function bookId(page, title) {
  const book = (await apiGet(page, '/books?stripForListView=true')).find(b => b.metadata?.title === title);
  if (!book) throw new Error(`No book called "${title}"`);
  return book.id;
}

/** Scrolls whichever element scrolls the page content (the window, or an inner panel). */
async function scrollBy(page, pixels) {
  await page.evaluate(dy => {
    const scroller = [...document.querySelectorAll('*')].find(el => el.scrollHeight > el.clientHeight + 50
      && ['auto', 'scroll'].includes(getComputedStyle(el).overflowY)) || document.scrollingElement;
    scroller.scrollBy(0, dy);
  }, pixels);
  await page.waitForTimeout(700);
}

/** Brings a section heading to just below the top of the view, so its chart fills the screenshot. */
async function scrollToHeading(page, text, selector = 'h3, h2') {
  const heading = page.locator(selector, {hasText: text}).first();
  // Charts further down render as they come into view, so scroll until the heading exists.
  for (let step = 0; step < 40 && await heading.count() === 0; step++) {
    await scrollBy(page, 700);
  }
  await heading.evaluate(el => el.scrollIntoView({block: 'start'}));
  await page.waitForTimeout(400);
  await scrollBy(page, -90);
  await page.waitForTimeout(1500); // charts animate as they come into view
}

/** How many books the signed-in user can see, straight from the API. */
async function bookCount(page) {
  return (await apiGet(page, '/books?stripForListView=true')).length;
}

const wanted = process.argv.slice(2);
const order = wanted.length ? wanted : Object.keys(scenes);
process.umask(0o022);
const siteHost = new URL(SITE).host.includes(':') ? new URL(SITE).host : `${new URL(SITE).hostname}:80`;
const browser = await chromium.launch({args: [`--host-resolver-rules=MAP ${siteHost} ${new URL(BASE).host}`]});
try {
  for (const name of order) {
    if (!scenes[name]) throw new Error(`No scene "${name}". Scenes: ${Object.keys(scenes).join(', ')}`);
    console.log(`scene ${name}`);
    const context = await browser.newContext({viewport: VIEWPORT, deviceScaleFactor: 1, colorScheme: 'dark'});
    const page = await context.newPage();
    if (name !== 'setup') await login(page);
    try {
      await scenes[name](page);
    } catch (e) {
      // What the page looked like when the scene failed, in the current (scratch) directory.
      await page.screenshot({path: `failed-${name}.png`}).catch(() => {});
      throw e;
    }
    await context.close();
  }
} finally {
  await browser.close();
}
