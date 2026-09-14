package org.booklore.service.kobo;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.service.restriction.BookAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KoboBookAccessServiceTest {

    private static final long BOOK_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long SHELF_ID = 9L;

    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private BookAccessService bookAccessService;
    @Mock
    private ShelfRepository shelfRepository;
    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private KoboBookAccessService koboBookAccessService;

    @BeforeEach
    void signIn() {
        lenient().when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(USER_ID).build());
    }

    private void koboShelf() {
        when(shelfRepository.findByUserIdAndName(USER_ID, ShelfType.KOBO.getName()))
                .thenReturn(Optional.of(ShelfEntity.builder().id(SHELF_ID).build()));
    }

    @Test
    void syncAllowedForABookOnTheUsersKoboShelf() {
        koboShelf();
        when(bookRepository.existsByIdAndShelves_Id(BOOK_ID, SHELF_ID)).thenReturn(true);

        assertThatCode(() -> koboBookAccessService.assertCanSync(BOOK_ID)).doesNotThrowAnyException();
        verify(bookAccessService).assertAccess(BOOK_ID);
    }

    @Test
    void syncRefusedForABookNotOnTheKoboShelf() {
        koboShelf();
        when(bookRepository.existsByIdAndShelves_Id(BOOK_ID, SHELF_ID)).thenReturn(false);

        assertThatThrownBy(() -> koboBookAccessService.assertCanSync(BOOK_ID))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Kobo shelf");
    }

    @Test
    void syncRefusedWhenTheUserHasNoKoboShelf() {
        when(shelfRepository.findByUserIdAndName(USER_ID, ShelfType.KOBO.getName())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> koboBookAccessService.assertCanSync(BOOK_ID)).isInstanceOf(APIException.class);
    }

    @Test
    void syncRefusedForARestrictedBookEvenOnTheShelf() {
        doThrow(ApiError.FORBIDDEN.createException("restricted")).when(bookAccessService).assertAccess(BOOK_ID);

        assertThatThrownBy(() -> koboBookAccessService.assertCanSync(BOOK_ID)).isInstanceOf(APIException.class);
        verifyNoInteractions(shelfRepository, bookRepository);
    }

    @Test
    void readingProgressOnlyNeedsAccessToTheBook() {
        when(bookRepository.existsById(BOOK_ID)).thenReturn(true);

        assertThat(koboBookAccessService.canRead(BOOK_ID)).isTrue();
        verify(bookAccessService).assertAccess(BOOK_ID);
        verifyNoInteractions(shelfRepository);
    }

    @Test
    void readingProgressForADeletedBookIsSkippedNotRefused() {
        when(bookRepository.existsById(BOOK_ID)).thenReturn(false);

        assertThat(koboBookAccessService.canRead(BOOK_ID)).isFalse();
        verifyNoInteractions(bookAccessService);
    }

    @Test
    void readingProgressForABookOutOfReachIsSkippedNotRefused() {
        when(bookRepository.existsById(BOOK_ID)).thenReturn(true);
        doThrow(ApiError.FORBIDDEN.createException("restricted")).when(bookAccessService).assertAccess(BOOK_ID);

        assertThat(koboBookAccessService.canRead(BOOK_ID)).isFalse();
    }
}
