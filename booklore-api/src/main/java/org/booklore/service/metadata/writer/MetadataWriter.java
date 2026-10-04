package org.booklore.service.metadata.writer;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

/** Cover methods take the exact file to write: a book can have an ebook and an audiobook file. */
public interface MetadataWriter {

    CoverWriteResult NO_COVER_IN_FILE = CoverWriteResult.skipped("Covers aren't written into this kind of file");

    void saveMetadataToFile(File file, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clearFlags);

    boolean shouldSaveMetadataToFile(File file);

    default CoverWriteResult replaceCoverImageFromUpload(File bookFile, MultipartFile file) {
        return NO_COVER_IN_FILE;
    }

    default CoverWriteResult replaceCoverImageFromBytes(File bookFile, byte[] file) {
        return NO_COVER_IN_FILE;
    }

    default CoverWriteResult replaceCoverImageFromUrl(File bookFile, String url) {
        return NO_COVER_IN_FILE;
    }

    BookFileType getSupportedBookType();
}
