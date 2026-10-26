-- Books put into one series by hand (Split / Merge on the series page), for same-name series the
-- series IDs and authors can't tell apart. Books with the same series name and series group are one
-- series; the value means nothing outside this library. Cleared when the book's series is renamed.
--
-- series_ids_checked_at: when the Fetch Series IDs task last asked Goodreads and Amazon for the
-- book's series IDs, so books whose series has none there aren't asked on every run.
ALTER TABLE "book_metadata"
    ADD COLUMN IF NOT EXISTS "series_group" VARCHAR(64),
    ADD COLUMN IF NOT EXISTS "series_ids_checked_at" TIMESTAMP;
