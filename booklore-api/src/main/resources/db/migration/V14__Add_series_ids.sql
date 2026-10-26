-- The series' own ID on Goodreads and its series ASIN on Amazon, fetched with the series name and
-- covered by its lock. Books that share a name but not these IDs are different series (two
-- authors' "Harem University"), and books that share an ID are one series whoever wrote them.
ALTER TABLE "book_metadata"
    ADD COLUMN IF NOT EXISTS "series_goodreads_id" VARCHAR(32),
    ADD COLUMN IF NOT EXISTS "series_asin" VARCHAR(20);
