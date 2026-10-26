package org.booklore.model.enums;

import lombok.Getter;

public enum TaskType {
    REFRESH_LIBRARY_METADATA(
            false,
            true,
            false,
            false,
            "Refresh Metadata",
            "Re-reads book information (title, author, cover, etc.) from your files and updates the Trove database."
    ),
    UPDATE_BOOK_RECOMMENDATIONS(
            false,
            true,
            true,
            false,
            "Update Book Recommendations",
            "Analyzes your library to generate personalized book recommendations based on the books you own."
    ),
    CLEANUP_DELETED_BOOKS(
            false,
            false,
            true,
            false,
            "Cleanup Deleted Books",
            "Permanently removes database entries for books you previously deleted from your libraries."
    ),
    SYNC_LIBRARY_FILES(
            false,
            false,
            true,
            false,
            "Sync Library Files",
            "Scans your library folders to detect new books and removes entries for files that no longer exist."
    ),
    BOOKDROP_PERIODIC_SCANNING(
            false,
            false,
            true,
            false,
            "Bookdrop Periodic Scanning",
            "Scans the bookdrop ingest folder for newly added files and queues them for bookdrop processing."
    ),
    CLEANUP_TEMP_METADATA(
            false,
            false,
            true,
            false,
            "Cleanup Temporary Metadata",
            "Removes temporary metadata files created during the bookdrop and manual metadata review processes."
    ),
    REFRESH_METADATA_MANUAL(
            false,
            true,
            false,
            true,
            "Refresh Metadata",
            "Updates metadata information for your selected books."
    ),
    CHECK_EBOOK_INTEGRITY(
            false,
            true,
            true,
            false,
            "Check Book File Integrity",
            "Verifies book files aren't corrupted - EPUB, CBZ, CB7, CBR, PDF, M4B/M4A and MP3 - by checking every entry's checksum or the file's structure, not just its header, and logs any that need to be re-imported."
    ),
    FILL_MISSING_PAGE_COUNTS(
            false,
            true,
            true,
            false,
            "Fill Missing Page Counts",
            "Gives books without a page count one from their files: exact for PDFs, comics and EPUBs with print page numbers, otherwise estimated from the word count, measured against your books that have one. Existing and locked counts are kept."
    ),
    REMOVE_EBOOK_ADS(
            false,
            true,
            true,
            false,
            "Remove Website Ads from EPUBs",
            "Two stages: first finds the EPUBs that OceanofPDF has stamped with its website link, then strips the link from every chapter of those books, plus the stray oceanofpdf.com file. Each cleaned book is checked before it replaces the original, and the original is kept in the ad-cleaner-backups folder. EPUBs already found clean or cleaned are skipped until their file changes."
    ),
    FETCH_MISSING_REVIEWS(
            false,
            true,
            true,
            false,
            "Fetch Missing Reviews",
            "Downloads reviews for books that have none, from the sources enabled under Public Reviews. Books with no reviews online are checked again after 90 days. Each run stops after 3 hours, or when the sources start blocking requests, and the next run carries on."
    ),
    FIX_EPUB_COVERS(
            false,
            true,
            true,
            false,
            "Fix Missing EPUB Covers",
            "Two stages: first finds the EPUBs whose own cover readers may not show - none declared, the image missing or unreadable, or saved as a different image type than declared - then writes Trove's cover into them, so downloaded copies have it. Each fixed file is read back to confirm the cover is there. EPUBs already found fine or fixed are skipped until their file changes."
    ),
    FETCH_SERIES_IDS(
            false,
            true,
            true,
            false,
            "Fetch Series IDs",
            "Looks up each series book's series on Goodreads and Amazon, using the Goodreads ID and ASIN it already has, so same-name series by different authors are told apart and guest-written books stay in their series. Only fills IDs that are missing, and only when the source names the same series. Books whose series has no ID there are asked again after 90 days. Each run stops after 3 hours, or when the sources start blocking requests, and the next run carries on."
    );

    @Getter
    private final boolean parallel;

    @Getter
    private final boolean async;

    @Getter
    private final boolean cronSupported;

    @Getter
    private final boolean hiddenFromUI;

    @Getter
    private final String name;

    @Getter
    private final String description;

    TaskType(boolean parallel, boolean async, boolean cronSupported, boolean hiddenFromUI, String name, String description) {
        this.parallel = parallel;
        this.async = async;
        this.cronSupported = cronSupported;
        this.hiddenFromUI = hiddenFromUI;
        this.name = name;
        this.description = description;
    }
}
