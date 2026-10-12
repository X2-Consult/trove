-- What the file-checking tasks ("Find/Remove Website Ads in EPUBs", "Find/Fix Missing EPUB Covers")
-- last found in each book file. A file found fine, or fixed, is skipped by later runs for as long as
-- its hash and the check's version are unchanged, so only new and changed files are opened again.

CREATE TABLE "book_file_check" (
    "id" BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    "book_file_id" BIGINT NOT NULL,
    "check_type" VARCHAR(32) NOT NULL,
    "result" VARCHAR(32) NOT NULL,
    "file_hash" VARCHAR(128),
    "check_version" INTEGER NOT NULL,
    "checked_at" TIMESTAMP NOT NULL,
    PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "book_file_check__uq_file_type" ON "book_file_check" ("book_file_id", "check_type");

ALTER TABLE "book_file_check" ADD CONSTRAINT "fk_book_file_check_book_file" FOREIGN KEY ("book_file_id") REFERENCES "book_file" ("id") ON DELETE CASCADE;
