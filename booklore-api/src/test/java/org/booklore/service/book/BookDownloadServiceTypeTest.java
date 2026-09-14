package org.booklore.service.book;

import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** iOS's player won't play a download it can't identify, so audiobooks go out as audio. */
class BookDownloadServiceTypeTest {

    private static BookFileEntity file(BookFileType type) {
        return BookFileEntity.builder().bookType(type).build();
    }

    @Test
    void audiobooksGoOutAsTheirAudioType() {
        assertThat(BookDownloadService.downloadType(file(BookFileType.AUDIOBOOK), Path.of("/b/Happy Prince.m4b"))).hasToString("audio/mp4");
        assertThat(BookDownloadService.downloadType(file(BookFileType.AUDIOBOOK), Path.of("/b/Tale.MP3"))).hasToString("audio/mpeg");
        assertThat(BookDownloadService.downloadType(file(BookFileType.AUDIOBOOK), Path.of("/b/Tale.opus"))).hasToString("audio/opus");
    }

    @Test
    void otherFormatsStillDownloadAsFiles() {
        assertThat(BookDownloadService.downloadType(file(BookFileType.EPUB), Path.of("/b/Emma.epub"))).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }
}
