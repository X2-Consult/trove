# Website and search engine visibility

Trove's public site is **https://x2-consult.github.io/trove/**: a landing page plus the in-app help pages.
It's there so that people asking search engines or AI assistants (ChatGPT, Claude, Perplexity, Gemini,
Copilot) for a self-hosted ebook, comic or audiobook server can find Trove.

## How it works

- **Source:** `site/` (landing page, `llms.txt`, `robots.txt`, IndexNow key file) and the help pages in
  `booklore-ui/public/docs/`.
- **Build:** `scripts/build-site.py` assembles them into one folder. It adds a description, canonical
  link and social-card tags to every help page, points the in-app "Back to Trove" link at the landing
  page, and generates `sitemap.xml`. To preview it: `python3 scripts/build-site.py --out /tmp/site`.
- **Publishing:** `.github/workflows/pages.yml` publishes the site when a push to `master` touches any of
  those files, and can also be run by hand from the Actions tab. After publishing, it sends every page in
  the sitemap to IndexNow, which Bing, Yandex, Naver, Seznam and others use. The key is public by design;
  its file is `site/ccd5fa2d9379bc0061d1a997539038ad.txt`.
- **Self-hosted servers stay out of search:** each server's `robots.txt` (`booklore-ui/public/robots.txt`)
  disallows all crawlers, and its pages send `X-Robots-Tag: noindex, nofollow` (`SecurityConfig`). Someone's
  private library, if it's reachable from the internet, won't turn up in search results or AI answers.
- **Keeping it current:** the landing page and `llms.txt` describe Trove's features and formats. Update
  them when a headline feature or a supported format changes.

## Known limitation

Crawlers only read `robots.txt` at the root of a domain (`x2-consult.github.io/robots.txt`), so the
site's own `/trove/robots.txt` and its sitemap pointer are ignored. Nothing is blocked, because no root
`robots.txt` means everything is allowed, but the sitemap has to be submitted by hand (below). A custom
domain would fix this: set `SITE_URL` in the workflow and add a `CNAME`.

## Status

### Done

- [x] Public site live on GitHub Pages: 58 pages, `sitemap.xml`, `llms.txt`, structured data (Oct 2026)
- [x] IndexNow submission after each publish (first run 17 Oct 2026, run 38997438827)
- [x] Self-hosted servers send `noindex` and a disallow-all `robots.txt`
- [x] README intro written around the terms people search for, with links to the site
- [x] GitHub About box: description, website and topics

### Outstanding

- [ ] **GitHub topic fix:** in the repo's About box, replace `self-hosted-runner` with `self-hosted`.
      The former is about GitHub Actions runners.
- [ ] **Google Search Console** (needed for Google Search, Gemini and Google's AI answers):
  1. At https://search.google.com/search-console, choose **Add property**, then **URL prefix**, and
     enter `https://x2-consult.github.io/trove/`.
  2. Choose **HTML tag** for verification and give Claude the `content="..."` value (it isn't secret).
     Claude adds the tag to `site/index.html`, then it's promoted to `master` so the site republishes.
  3. Back in Search Console, click **Verify**, then **Sitemaps**, and submit `sitemap.xml`.
- [ ] **Bing Webmaster Tools** (optional, for Bing's search reports; IndexNow already gets pages
      indexed): at https://www.bing.com/webmasters, sign in and choose **Import from Google Search
      Console** once Google is verified.
- [ ] **Listings on other sites.** AI assistants lean heavily on what other sites say about a project,
      so these matter as much as the site itself:
  - [ ] awesome-selfhosted (https://github.com/awesome-selfhosted/awesome-selfhosted-data), under the
        Document Management / E-books section. Read its contributing rules first.
  - [ ] selfh.st (https://selfh.st/apps/): submit the app.
  - [ ] AlternativeTo (https://alternativeto.net): add Trove as an alternative to Calibre-Web, Kavita,
        Komga and BookLore.
  - [ ] A launch post on r/selfhosted.
- [ ] **Check after a few weeks:** ask ChatGPT, Claude and Perplexity for "a self-hosted ebook library
      server" or "a BookLore alternative" and see whether Trove comes up. Use Bing's `site:` search,
      `site:x2-consult.github.io/trove`, to confirm the pages are indexed.
