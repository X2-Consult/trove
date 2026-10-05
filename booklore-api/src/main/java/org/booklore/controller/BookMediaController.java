package org.booklore.controller;

import org.booklore.service.AuthorMetadataService;
import org.booklore.config.security.annotation.CheckBookAccess;
import org.booklore.service.book.BookService;
import org.booklore.service.bookdrop.BookDropService;
import org.booklore.service.reader.CbxReaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;

@Tag(name = "Book Media", description = "Endpoints for retrieving book media such as covers, thumbnails, and pages")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/media")
public class BookMediaController {

    private final BookService bookService;
    private final CbxReaderService cbxReaderService;
    private final BookDropService bookDropService;
    private final AuthorMetadataService authorMetadataService;

    @Operation(summary = "Get book thumbnail", description = "Retrieve the thumbnail image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Book thumbnail returned successfully")
    @GetMapping("/book/{bookId}/thumbnail")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getBookThumbnail(@Parameter(description = "ID of the book") @PathVariable long bookId, WebRequest request) {
        return withLastModified(bookService.getBookThumbnail(bookId), request);
    }

    @Operation(summary = "Get book cover", description = "Retrieve the cover image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Book cover returned successfully")
    @GetMapping("/book/{bookId}/cover")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getBookCover(@Parameter(description = "ID of the book") @PathVariable long bookId, WebRequest request) {
        return withLastModified(bookService.getBookCover(bookId), request);
    }

    @Operation(summary = "Get audiobook thumbnail", description = "Retrieve the audiobook thumbnail image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Audiobook thumbnail returned successfully")
    @GetMapping("/book/{bookId}/audiobook-thumbnail")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getAudiobookThumbnail(@Parameter(description = "ID of the book") @PathVariable long bookId, WebRequest request) {
        return withLastModified(bookService.getAudiobookThumbnail(bookId), request);
    }

    @Operation(summary = "Get audiobook cover", description = "Retrieve the audiobook cover image for a specific book.")
    @ApiResponse(responseCode = "200", description = "Audiobook cover returned successfully")
    @GetMapping("/book/{bookId}/audiobook-cover")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> getAudiobookCover(@Parameter(description = "ID of the book") @PathVariable long bookId, WebRequest request) {
        return withLastModified(bookService.getAudiobookCover(bookId), request);
    }

    @Operation(summary = "Get CBX page as image", description = "Retrieve a specific page from a CBX book as an image.")
    @ApiResponse(responseCode = "200", description = "CBX page image returned successfully")
    @GetMapping("/book/{bookId}/cbx/pages/{pageNumber}")
    @CheckBookAccess(bookIdParam = "bookId")
    public void getCbxPage(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @Parameter(description = "Page number to retrieve") @PathVariable int pageNumber,
            @Parameter(description = "Optional book type for alternative format (e.g., PDF, CBX)") @RequestParam(required = false) String bookType,
            HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        cbxReaderService.streamPageImage(bookId, bookType, pageNumber, response.getOutputStream());
    }

    @Operation(summary = "Get author photo", description = "Retrieve the photo for a specific author.")
    @ApiResponse(responseCode = "200", description = "Author photo returned successfully")
    @GetMapping("/author/{authorId}/photo")
    public ResponseEntity<Resource> getAuthorPhoto(@Parameter(description = "ID of the author") @PathVariable long authorId, WebRequest request) {
        Resource photo = authorMetadataService.getAuthorPhoto(authorId);
        if (photo == null) {
            return ResponseEntity.notFound().build();
        }
        if (notModified(photo, request)) {
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(photo);
    }

    @Operation(summary = "Get author thumbnail", description = "Retrieve the thumbnail for a specific author.")
    @ApiResponse(responseCode = "200", description = "Author thumbnail returned successfully")
    @GetMapping("/author/{authorId}/thumbnail")
    public ResponseEntity<Resource> getAuthorThumbnail(@Parameter(description = "ID of the author") @PathVariable long authorId, WebRequest request) {
        Resource thumbnail = authorMetadataService.getAuthorThumbnail(authorId);
        if (thumbnail == null) {
            return ResponseEntity.notFound().build();
        }
        if (notModified(thumbnail, request)) {
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(thumbnail);
    }

    @Operation(summary = "Get bookdrop cover", description = "Retrieve the cover image for a specific bookdrop file.")
    @ApiResponse(responseCode = "200", description = "Bookdrop cover returned successfully")
    @GetMapping("/bookdrop/{bookdropId}/cover")
    public ResponseEntity<Resource> getBookdropCover(@Parameter(description = "ID of the bookdrop file") @PathVariable long bookdropId) {
        Resource file = bookDropService.getBookdropCover(bookdropId);
        String contentDisposition = "inline; filename=\"cover.jpg\"; filename*=UTF-8''cover.jpg";
        return (file != null)
                ? ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(MediaType.IMAGE_JPEG)
                .body(file)
                : ResponseEntity.noContent().build();
    }

    /**
     * Answers 304 when the browser's copy is current (Last-Modified / If-Modified-Since), so an expired
     * cached image costs a header round trip rather than the whole file. See ImageCachingFilter.
     */
    private static ResponseEntity<Resource> withLastModified(Resource resource, WebRequest request) {
        if (notModified(resource, request)) {
            return null;
        }
        return ResponseEntity.ok(resource);
    }

    /** Also sets Last-Modified on the response; true (with status 304 set) if the client is current. */
    private static boolean notModified(Resource resource, WebRequest request) {
        long lastModified;
        try {
            lastModified = resource.lastModified();
        } catch (IOException e) {
            return false;
        }
        return lastModified > 0 && request.checkNotModified(lastModified);
    }
}
