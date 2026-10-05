-- Apple Books ID, rating and review count, from the Apple Books metadata source, each with its own
-- lock like the other providers' fields. Same column names as Grimmory (V147 there).
ALTER TABLE "book_metadata"
    ADD COLUMN IF NOT EXISTS "applebooks_id" VARCHAR(100),
    ADD COLUMN IF NOT EXISTS "applebooks_rating" DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS "applebooks_review_count" INTEGER,
    ADD COLUMN IF NOT EXISTS "applebooks_id_locked" BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS "applebooks_rating_locked" BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS "applebooks_review_count_locked" BOOLEAN DEFAULT FALSE;
