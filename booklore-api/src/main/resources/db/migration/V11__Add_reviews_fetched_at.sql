-- When the "Fetch Missing Reviews" task last asked the review sources about a book. Books that came
-- back with no reviews are left alone for a while instead of being asked again on every run.
ALTER TABLE "book_metadata" ADD COLUMN "reviews_fetched_at" TIMESTAMP;
