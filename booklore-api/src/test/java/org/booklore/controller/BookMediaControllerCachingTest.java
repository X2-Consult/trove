package org.booklore.controller;

import org.booklore.service.AuthorMetadataService;
import org.booklore.service.book.BookService;
import org.booklore.service.bookdrop.BookDropService;
import org.booklore.service.reader.CbxReaderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookMediaControllerCachingTest {

    @TempDir
    Path dir;

    @Test
    void sendsLastModifiedAndAnswers304WhenTheBrowserIsCurrent() throws Exception {
        Path thumb = Files.write(dir.resolve("thumbnail.jpg"), new byte[]{1, 2, 3});
        Instant modified = Instant.parse("2026-10-01T10:00:00Z");
        Files.setLastModifiedTime(thumb, FileTime.from(modified));
        BookService bookService = mock(BookService.class);
        when(bookService.getBookThumbnail(5L)).thenReturn(new FileSystemResource(thumb));
        BookMediaController controller = new BookMediaController(bookService, mock(CbxReaderService.class),
                mock(BookDropService.class), mock(AuthorMetadataService.class));

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/media/book/5/thumbnail");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        ResponseEntity<?> full = controller.getBookThumbnail(5L, new ServletWebRequest(first, firstResponse));
        assertThat(full).isNotNull();
        assertThat(firstResponse.getDateHeader("Last-Modified")).isEqualTo(modified.toEpochMilli());

        MockHttpServletRequest again = new MockHttpServletRequest("GET", "/api/v1/media/book/5/thumbnail");
        again.addHeader("If-Modified-Since", modified.toEpochMilli());
        MockHttpServletResponse againResponse = new MockHttpServletResponse();
        ResponseEntity<?> cached = controller.getBookThumbnail(5L, new ServletWebRequest(again, againResponse));
        assertThat(cached).isNull();
        assertThat(againResponse.getStatus()).isEqualTo(304);
    }
}
