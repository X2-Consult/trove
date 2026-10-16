#!/usr/bin/env python3
"""Build Trove's public website (GitHub Pages) into a folder.

The site is the landing page, robots.txt and llms.txt in site/, plus the in-app help pages from
booklore-ui/public/docs. The help pages are copied with what search engines and AI assistants
need added (a description, a canonical link and social-card tags), and the in-app "Back to Trove"
link pointed at the landing page instead. A sitemap is generated.

    python3 scripts/build-site.py [--out _site]

SITE_URL (default https://x2-consult.github.io/trove/) and VERSION (default: the latest git tag)
can be set in the environment, e.g. when the site moves to its own domain.
"""
import argparse
import html
import os
import re
import shutil
import subprocess
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
DOCS = ROOT / "booklore-ui" / "public" / "docs"
BRAND = ROOT / "assets" / "brand" / "trove"
LOGOS = ["trove-logo-light.svg", "trove-logo-dark.svg", "trove-mark-gradient.svg"]
TEMPLATED = {".html", ".txt", ".xml"}

BACK_LINK = '<a class="back-to-app" href="/">Back to Trove</a>'


def git(*args: str) -> str:
    try:
        return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def last_changed(path: Path) -> str:
    return git("log", "-1", "--format=%cs", "--", str(path)) or date.today().isoformat()


def description(page: str) -> str:
    """The page's lede paragraph as plain text, cut to about 160 characters at a word boundary."""
    m = re.search(r'<p class="lede">(.*?)</p>', page, re.S)
    text = html.unescape(re.sub(r"<[^>]+>", "", m.group(1) if m else "")).strip()
    text = re.sub(r"\s+", " ", text)
    if len(text) > 160:
        text = text[:157].rsplit(" ", 1)[0].rstrip(",;:") + "..."
    return text


def fill(text: str, values: dict[str, str]) -> str:
    for key, value in values.items():
        text = text.replace("{{" + key + "}}", value)
    return text


def build_docs_page(src: Path, rel: str, site_url: str) -> str:
    page = src.read_text(encoding="utf-8")
    url = site_url + "docs/" + (rel[: -len("index.html")] if rel.endswith("index.html") else rel)
    title = re.search(r"<title>(.*?)</title>", page, re.S)
    title = html.unescape(title.group(1)) if title else "Trove Help"
    desc = description(page)
    tags = [f'<meta name="description" content="{html.escape(desc)}">' if desc else "",
            f'<link rel="canonical" href="{url}">',
            '<meta property="og:type" content="article">',
            f'<meta property="og:title" content="{html.escape(title)}">',
            f'<meta property="og:description" content="{html.escape(desc)}">' if desc else "",
            f'<meta property="og:url" content="{url}">',
            '<meta property="og:site_name" content="Trove">']
    head = "\n".join(t for t in tags if t)
    if "</title>" not in page:
        raise SystemExit(f"{rel}: no <title>, can't add the page description")
    page = page.replace("</title>", "</title>\n" + head, 1)
    root = re.search(r'<body[^>]*data-root="([^"]*)"', page)
    page = page.replace(BACK_LINK, f'<a class="back-to-app" href="{root.group(1) if root else ""}../">Trove home</a>')
    return page


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="_site", help="output folder (replaced)")
    out = Path(parser.parse_args().out).resolve()

    site_url = os.environ.get("SITE_URL") or "https://x2-consult.github.io/trove/"
    site_url = site_url.rstrip("/") + "/"
    version = os.environ.get("VERSION") or git("describe", "--tags", "--abbrev=0") or "latest"
    values = {"SITE_URL": site_url, "VERSION": version}

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    pages = []  # (url, lastmod)

    for src in sorted(SITE.rglob("*")):
        if src.is_dir():
            continue
        dest = out / src.relative_to(SITE)
        dest.parent.mkdir(parents=True, exist_ok=True)
        if src.suffix in TEMPLATED:
            dest.write_text(fill(src.read_text(encoding="utf-8"), values), encoding="utf-8")
        else:
            shutil.copy2(src, dest)
    pages.append((site_url, last_changed(SITE / "index.html")))

    for name in LOGOS:
        shutil.copy2(BRAND / name, out / name)

    for src in sorted(DOCS.rglob("*")):
        if src.is_dir():
            continue
        rel = src.relative_to(DOCS).as_posix()
        dest = out / "docs" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if src.suffix == ".html":
            dest.write_text(build_docs_page(src, rel, site_url), encoding="utf-8")
            url = site_url + "docs/" + (rel[: -len("index.html")] if rel.endswith("index.html") else rel)
            pages.append((url, last_changed(src)))
        else:
            shutil.copy2(src, dest)

    urls = "\n".join(f"  <url><loc>{html.escape(u)}</loc><lastmod>{d}</lastmod></url>" for u, d in pages)
    (out / "sitemap.xml").write_text('<?xml version="1.0" encoding="UTF-8"?>\n'
                                     '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
                                     f"{urls}\n</urlset>\n", encoding="utf-8")
    (out / ".nojekyll").write_text("")  # serve the files as they are
    print(f"Built {len(pages)} pages into {out} for {site_url} (Trove {version})")


if __name__ == "__main__":
    main()
