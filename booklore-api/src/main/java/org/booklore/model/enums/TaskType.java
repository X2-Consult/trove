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
