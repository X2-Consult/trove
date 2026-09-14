-- Marks a page count Trove worked out from the book's file (the "Fill Missing Page Counts" task)
-- rather than one read from the file's metadata or a metadata provider. Estimates are left out when
-- measuring words per page, and a real count from a later refresh replaces them.
ALTER TABLE "book_metadata" ADD COLUMN "page_count_estimated" BOOLEAN NOT NULL DEFAULT FALSE;
