// Builds the help pages' sidebar and heading links. Pages read fine without it; this only adds
// navigation. To add a page, add it to NAV (paths are relative to /docs).
const NAV = [
  ['Start here', [
    ['getting-started.html', 'Getting started'],
    ['installation.html', 'Installation'],
    ['initial-setup.html', 'Admin account'],
    ['dashboard.html', 'Dashboard'],
  ]],
  ['Libraries', [
    ['library/setup-first-library.html', 'Your first library'],
    ['library/edit-library.html', 'Managing libraries'],
    ['library/folder-structure.html', 'Folder structure'],
    ['library/organization-modes.html', 'Organization modes'],
    ['library/network-storage.html', 'Network storage'],
    ['library/physical-books.html', 'Physical books'],
    ['library/duplicate-detection.html', 'Duplicate detection'],
    ['library-stats.html', 'Library statistics'],
  ]],
  ['Adding books', [
    ['bookdrop.html', 'Bookdrop'],
    ['bookdrop-advanced.html', 'Bookdrop tools'],
  ]],
  ['Browsing', [
    ['book-browser/grid.html', 'Grid view'],
    ['book-browser/table.html', 'Table view'],
    ['authors.html', 'Authors'],
    ['series.html', 'Series'],
    ['shelf.html', 'Shelves'],
    ['magic-shelf.html', 'Magic shelves'],
    ['notebook.html', 'Notebook'],
  ]],
  ['Metadata', [
    ['metadata/metadata-center.html', 'Book page'],
    ['metadata/metadata-manager.html', 'Metadata Manager'],
    ['metadata/metadata-settings.html', 'Metadata settings'],
    ['metadata/metadata-fetch-configuration.html', 'Library metadata'],
    ['metadata/file-naming-patterns.html', 'File naming patterns'],
    ['metadata/sidecar-files.html', 'Sidecar files'],
    ['metadata/amazon-cookie.html', 'Amazon cookie'],
    ['metadata/hardcover-token.html', 'Hardcover token'],
    ['metadata/lubimyczytac.html', 'Lubimyczytac'],
    ['metadata/ranobedb.html', 'RanobeDB'],
  ]],
  ['Reading', [
    ['readers/epub-reader.html', 'eBook reader'],
    ['readers/pdf-reader.html', 'PDF reader'],
    ['readers/cbx-reader.html', 'Comic reader'],
    ['readers/audiobook-player.html', 'Audiobook player'],
    ['reader-preferences.html', 'Reader preferences'],
    ['view-preferences.html', 'View preferences'],
    ['reading-stats.html', 'Reading statistics'],
  ]],
  ['Devices and apps', [
    ['integration/kobo.html', 'Kobo'],
    ['integration/koreader.html', 'KOReader'],
    ['integration/opds.html', 'OPDS'],
    ['integration/komga-api.html', 'Komga API'],
    ['email-setup.html', 'Sending by email'],
  ]],
  ['Users and sign-in', [
    ['tools/user-management.html', 'Users and permissions'],
    ['content-restrictions.html', 'Content restrictions'],
    ['authentication/overview.html', 'Single sign-on (OIDC)'],
    ['authentication/authentik.html', 'Authentik'],
    ['authentication/authelia.html', 'Authelia'],
    ['tools/api-tokens.html', 'API tokens'],
  ]],
  ['Administration', [
    ['tools/global-preferences.html', 'Application settings'],
    ['tools/cover-art-settings.html', 'Cover settings'],
    ['tools/devices.html', 'Device settings'],
    ['tools/task-manager.html', 'Tasks'],
    ['tools/audit-logs.html', 'Audit logs'],
    ['tools/custom-icons.html', 'Custom icons'],
    ['tools/custom-fonts.html', 'Custom fonts'],
  ]],
];

(function () {
  const root = document.body.dataset.root || '';
  const here = location.pathname.replace(/^.*\/docs\//, '');
  const nav = document.getElementById('docs-nav');

  if (nav) {
    const toggle = document.createElement('details');
    toggle.className = 'nav-toggle';
    toggle.open = window.matchMedia('(min-width: 861px)').matches;
    const label = document.createElement('summary');
    label.textContent = 'Help topics';
    toggle.append(label);
    for (const [section, pages] of NAV) {
      const group = document.createElement('details');
      group.open = true;
      const title = document.createElement('summary');
      title.textContent = section;
      const list = document.createElement('ul');
      for (const [href, text] of pages) {
        const item = document.createElement('li');
        const link = document.createElement('a');
        link.href = root + href;
        link.textContent = text;
        if (href === here) link.setAttribute('aria-current', 'page');
        item.append(link);
        list.append(item);
      }
      group.append(title, list);
      toggle.append(group);
    }
    nav.append(toggle);
    // Bring the current page into view within the sidebar (not the page itself).
    const current = nav.querySelector('[aria-current="page"]');
    if (current && nav.scrollHeight > nav.clientHeight) nav.scrollTop = current.offsetTop - nav.clientHeight / 2;
  }

  // A "#" link on each section heading, for sharing a link to that part of the page.
  for (const heading of document.querySelectorAll('.doc h2[id], .doc h3[id], .doc h4[id]')) {
    const anchor = document.createElement('a');
    anchor.className = 'anchor';
    anchor.href = '#' + heading.id;
    anchor.textContent = '#';
    anchor.setAttribute('aria-label', 'Link to this section');
    heading.append(anchor);
  }
})();
