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
  await page.goto(`${BASE}/login`, {waitUntil: 'networkidle'});
  await page.fill('#username', ADMIN.username);
  await page.fill(LOGIN_PASSWORD, PASSWORD);
  await page.click('button[type=submit]');
  await page.waitForURL(url => !url.toString().includes('/login'), {timeout: 30000});
  await page.waitForLoadState('networkidle');
}

async function open(page, route) {
  await page.goto(`${BASE}${route}`, {waitUntil: 'networkidle'});
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
async function scrollToHeading(page, text) {
  const heading = page.locator('h3, h2', {hasText: text}).first();
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
const browser = await chromium.launch();
try {
  for (const name of order) {
    if (!scenes[name]) throw new Error(`No scene "${name}". Scenes: ${Object.keys(scenes).join(', ')}`);
    console.log(`scene ${name}`);
    const context = await browser.newContext({viewport: VIEWPORT, deviceScaleFactor: 1, colorScheme: 'dark'});
    const page = await context.newPage();
    if (name !== 'setup') await login(page);
    await scenes[name](page);
    await context.close();
  }
} finally {
  await browser.close();
}
