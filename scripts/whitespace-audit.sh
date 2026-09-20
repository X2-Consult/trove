#!/usr/bin/env bash
#
# Read-only audit for stray whitespace in series names, titles and file names.
#
# Two separate causes leave spaces on the end of names, and this tells them apart by
# printing the trailing byte in hex:
#
#   20    a plain space, baked into files organised before 6bc28ee6 (2026-09-13), which
#         is when PathPatternResolver started stripping trailing whitespace from path
#         components. Fixing the code won't rename these; a bulk file move will.
#   c2a0  a non-breaking space (U+00A0), which still slips through today: the DB trim in
#         BookMetadataEntity.trimStringFields() uses String.trim() and sanitize() uses
#         \s+, and neither is Unicode-aware. Jsoup's text() hands these to us verbatim
#         from Amazon/Goodreads series names.
#
# Runs SELECTs and find only - it changes nothing.
#
# Usage:  sudo -u postgres bash scripts/whitespace-audit.sh /srv/trove/library
#         TROVE_PSQL="psql -h localhost -U trove" PGPASSWORD=... bash scripts/whitespace-audit.sh /srv/trove/library
#
# Env:    TROVE_DB    database name (default: trove)
#         TROVE_PSQL  psql command with connection flags (default: psql, i.e. peer auth)
#
set -uo pipefail

DB="${TROVE_DB:-trove}"
# Override with e.g. TROVE_PSQL="psql -h localhost -U trove" if not running as the postgres role.
if [ -n "${TROVE_PSQL:-}" ]; then read -ra PSQL <<< "$TROVE_PSQL"; else PSQL=(psql); fi
PSQL+=(-X -q -d "$DB" -v ON_ERROR_STOP=1)

# Every Unicode space-ish character Java's trim()/\s does NOT remove, plus the ASCII ones it does.
WS="E' \\t\\n\\r\\u00A0\\u1680\\u2000\\u2001\\u2002\\u2003\\u2004\\u2005\\u2006\\u2007\\u2008\\u2009\\u200A\\u200B\\u202F\\u205F\\u2060\\u3000\\uFEFF'"

echo "=== 1. How many rows have leading/trailing whitespace ==="
"${PSQL[@]}" <<SQL
SELECT
  count(*) FILTER (WHERE series_name IS NOT NULL)                          AS rows_with_series,
  count(*) FILTER (WHERE series_name <> btrim(series_name, $WS))           AS series_name_bad,
  count(*) FILTER (WHERE title       <> btrim(title, $WS))                 AS title_bad,
  count(*) FILTER (WHERE subtitle    <> btrim(subtitle, $WS))              AS subtitle_bad,
  count(*) FILTER (WHERE publisher   <> btrim(publisher, $WS))             AS publisher_bad,
  count(*) FILTER (WHERE series_name ~ '[[:space:]]{2,}')                  AS series_double_space
FROM book_metadata;
SELECT count(*) AS author_names_bad FROM author WHERE name <> btrim(name, $WS);
SQL

echo
echo "=== 2. Exactly which character is trailing (hex, UTF-8) ==="
"${PSQL[@]}" <<SQL
SELECT encode(convert_to(right(series_name, 1), 'UTF8'), 'hex') AS trailing_byte_hex,
       count(*) AS n,
       min(series_name) AS example
FROM book_metadata
WHERE series_name IS NOT NULL AND series_name <> btrim(series_name, $WS)
GROUP BY 1 ORDER BY n DESC;
SQL

echo
echo "=== 3. Sample bad series names (delimited with |) ==="
"${PSQL[@]}" <<SQL
SELECT DISTINCT '|' || series_name || '|' AS value
FROM book_metadata
WHERE series_name IS NOT NULL AND series_name <> btrim(series_name, $WS)
ORDER BY 1 LIMIT 25;
SQL

echo
echo "=== 4. Stored file names / sub-paths with stray whitespace ==="
"${PSQL[@]}" <<SQL
SELECT count(*) FILTER (WHERE file_name     <> btrim(file_name, $WS))     AS file_name_bad,
       count(*) FILTER (WHERE file_sub_path ~ ('[' || $WS || ']/'))       AS subpath_component_bad,
       count(*) FILTER (WHERE file_sub_path <> btrim(file_sub_path, $WS)) AS subpath_end_bad
FROM book_file;
SELECT '|' || file_sub_path || '/' || file_name || '|' AS path
FROM book_file
WHERE file_name <> btrim(file_name, $WS)
   OR file_sub_path <> btrim(file_sub_path, $WS)
   OR file_sub_path ~ ('[' || $WS || ']/')
LIMIT 25;
SQL

echo
echo "=== 5. Actual files/folders on disk ==="
for root in "$@"; do
  echo "--- $root ---"
  echo "trailing plain space:   $(find "$root" -name '* ' 2>/dev/null | wc -l)"
  echo "contains NBSP (U+00A0): $(find "$root" -name "*"$' '"*" 2>/dev/null | wc -l)"
  echo "sample:"
  find "$root" \( -name '* ' -o -name "*"$' '"*" \) 2>/dev/null | head -20 | while read -r p; do printf '  |%s|\n' "$p"; done
done
