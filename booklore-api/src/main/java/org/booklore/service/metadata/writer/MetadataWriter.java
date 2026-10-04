package org.booklore.service.metadata.writer;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

public interface MetadataWriter {

    CoverWriteResult NO_COVER_IN_FILE = CoverWriteResult.skipped("Covers aren't written into this kind of file");

    void saveMetadataToFile(File file, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clearFlags);

    boolean shouldSaveMetadataToFile(File file);

    default CoverWriteResult replaceCoverImageFromUpload(BookEntity bookEntity, MultipartFile file) {
        return NO_COVER_IN_FILE;
    }

    default CoverWriteResult replaceCoverImageFromBytes(BookEntity bookEntity, byte[] file) {
        return NO_COVER_IN_FILE;
    }

    default CoverWriteResult replaceCoverImageFromUrl(BookEntity bookEntity, String url) {
        return NO_COVER_IN_FILE;
    }

    BookFileType getSupportedBookType();
}
