package org.booklore.service.kobo;

import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.service.restriction.BookAccessService;
import org.springframework.stereotype.Service;

/**
 * What a Kobo may fetch by book ID. Kobo requests authenticate as the owner of the sync token, but
 * the endpoints themselves would serve any book to anyone with a token. A download or metadata
 * request needs the book on that user's Kobo shelf (the only books a sync offers) and within their
 * libraries and content restrictions, the same check as the web app. Reading progress only needs
 * the second, so a book that has just left the shelf can still report where its reader stopped.
 */
@Service
@RequiredArgsConstructor
public class KoboBookAccessService {

    private final AuthenticationService authenticationService;
    private final BookAccessService bookAccessService;
    private final ShelfRepository shelfRepository;
    private final BookRepository bookRepository;

    /** For downloads and book metadata: on the user's Kobo shelf, and theirs to see. */
    public void assertCanSync(long bookId) {
        assertCanRead(bookId);
        Long userId = authenticationService.getAuthenticatedUser().getId();
        boolean onKoboShelf = shelfRepository.findByUserIdAndName(userId, ShelfType.KOBO.getName())
                .map(shelf -> bookRepository.existsByIdAndShelves_Id(bookId, shelf.getId()))
                .orElse(false);
        if (!onKoboShelf) {
            throw ApiError.FORBIDDEN.createException("This book isn't on your Kobo shelf.");
        }
    }

    /** For reading progress: a book in the user's libraries that their content restrictions allow. */
    public void assertCanRead(long bookId) {
        bookAccessService.assertAccess(bookId);
    }
}
