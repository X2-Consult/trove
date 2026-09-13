-- Moving a book to another library updated the book's folder but not its files' copy of it
-- (book_file.library_path_id, added in V7). Such a file still belonged to the old folder, so
-- deleting that folder or its library deleted the file row too (ON DELETE CASCADE), leaving the
-- book with no file: it then showed as a physical copy. Point every file back at its book's folder.
-- Skips a row whose corrected identity another file row already has, so the unique identity
-- index, where an install has it, can't reject the update.
UPDATE "book_file" bf
SET "library_path_id" = b."library_path_id"
FROM "book" b
WHERE b."id" = bf."book_id"
  AND b."library_path_id" IS NOT NULL
  AND bf."library_path_id" IS DISTINCT FROM b."library_path_id"
  AND NOT EXISTS (
      SELECT 1 FROM "book_file" o
      WHERE o."library_path_id" = b."library_path_id"
        AND o."file_sub_path" = bf."file_sub_path"
        AND o."file_name" = bf."file_name");
