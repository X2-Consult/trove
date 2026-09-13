package org.booklore.service.kobo;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.KoboUserSettingsEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.KoboUserSettingsRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.restriction.ContentRestrictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KoboAutoShelfServiceTest {

    @Mock
    private KoboUserSettingsRepository koboUserSettingsRepository;

    @Mock
    private ShelfRepository shelfRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KoboCompatibilityService koboCompatibilityService;

    @Mock
    private ContentRestrictionService contentRestrictionService;

    @InjectMocks
    private KoboAutoShelfService koboAutoShelfService;

    private LibraryEntity library;
    private BookEntity testBook;
    private BookLoreUserEntity testUser1;
    private BookLoreUserEntity testUser2;
    private ShelfEntity koboShelf1;
    private ShelfEntity koboShelf2;
    private KoboUserSettingsEntity settings1;
    private KoboUserSettingsEntity settings2;

    @BeforeEach
    void setUp() {
        // No content restrictions unless a test sets some.
        lenient().when(contentRestrictionService.applyRestrictions(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        library = LibraryEntity.builder().id(5L).name("Books").build();
        testBook = BookEntity.builder()
                .id(1L)
                .library(library)
                .shelves(new HashSet<>())
                .build();

        testUser1 = BookLoreUserEntity.builder()
                .id(100L)
                .libraries(new ArrayList<>(List.of(library)))
                .isDefaultPassword(false).build();

        testUser2 = BookLoreUserEntity.builder()
                .id(200L)
                .libraries(new ArrayList<>(List.of(library)))
                .isDefaultPassword(false).build();

        lenient().when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<BookLoreUserEntity> found = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                if (Long.valueOf(100L).equals(id)) found.add(testUser1);
                if (Long.valueOf(200L).equals(id)) found.add(testUser2);
            }
            return found;
        });

        koboShelf1 = ShelfEntity.builder()
                .id(10L)
                .name(ShelfType.KOBO.getName())
                .user(testUser1)
                .build();

        koboShelf2 = ShelfEntity.builder()
                .id(20L)
                .name(ShelfType.KOBO.getName())
                .user(testUser2)
                .build();

        settings1 = KoboUserSettingsEntity.builder()
                .userId(100L)
                .autoAddToShelf(true)
                .syncEnabled(true)
                .build();

        settings2 = KoboUserSettingsEntity.builder()
                .userId(200L)
                .autoAddToShelf(true)
                .syncEnabled(true)
                .build();
    }

    @Test
    void autoAddBookToKoboShelves_withNullBookId_shouldReturnEarly() {
        koboAutoShelfService.autoAddBookToKoboShelves(null);

        verify(bookRepository, never()).findById(anyLong());
        verify(koboUserSettingsRepository, never()).findByAutoAddToShelfTrueAndSyncEnabledTrue();
        verify(shelfRepository, never()).findByUserIdInAndName(anyList(), anyString());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void autoAddBookToKoboShelves_withNonExistentBook_shouldReturnEarly() {
        when(bookRepository.findById(1L)).thenReturn(Optional.empty());

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).findById(1L);
        verify(koboUserSettingsRepository, never()).findByAutoAddToShelfTrueAndSyncEnabledTrue();
        verify(shelfRepository, never()).findByUserIdInAndName(anyList(), anyString());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void autoAddBookToKoboShelves_withIncompatibleBook_shouldReturnEarly() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(false);

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).findById(1L);
        verify(koboCompatibilityService).isBookSupportedForKobo(testBook);
        verify(koboUserSettingsRepository, never()).findByAutoAddToShelfTrueAndSyncEnabledTrue();
        verify(shelfRepository, never()).findByUserIdInAndName(anyList(), anyString());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void autoAddBookToKoboShelves_withNoEligibleUsers_shouldReturnEarly() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(Collections.emptyList());

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).findById(1L);
        verify(koboCompatibilityService).isBookSupportedForKobo(testBook);
        verify(koboUserSettingsRepository).findByAutoAddToShelfTrueAndSyncEnabledTrue();
        verify(shelfRepository, never()).findByUserIdInAndName(anyList(), anyString());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void autoAddBookToKoboShelves_successfully_shouldAddBookToShelves() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName()))
                .thenReturn(List.of(koboShelf1, koboShelf2));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).findById(1L);
        verify(koboCompatibilityService).isBookSupportedForKobo(testBook);
        verify(koboUserSettingsRepository).findByAutoAddToShelfTrueAndSyncEnabledTrue();
        verify(shelfRepository).findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName());
        verify(bookRepository).save(testBook);

        assert testBook.getShelves().contains(koboShelf1);
        assert testBook.getShelves().contains(koboShelf2);
        assert testBook.getShelves().size() == 2;
    }

    @Test
    void autoAddBookToKoboShelves_withOneUserMissingShelf_shouldAddOnlyToExistingShelves() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName()))
                .thenReturn(List.of(koboShelf1));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).save(testBook);
        assert testBook.getShelves().contains(koboShelf1);
        assert !testBook.getShelves().contains(koboShelf2);
        assert testBook.getShelves().size() == 1;
    }

    @Test
    void autoAddBookToKoboShelves_withBookAlreadyOnShelf_shouldNotDuplicate() {
        testBook.getShelves().add(koboShelf1);

        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName()))
                .thenReturn(List.of(koboShelf1, koboShelf2));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).save(testBook);
        assert testBook.getShelves().contains(koboShelf1);
        assert testBook.getShelves().contains(koboShelf2);
        assert testBook.getShelves().size() == 2;
    }

    @Test
    void autoAddBookToKoboShelves_withBookOnAllShelves_shouldNotSave() {
        testBook.getShelves().add(koboShelf1);
        testBook.getShelves().add(koboShelf2);

        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName()))
                .thenReturn(List.of(koboShelf1, koboShelf2));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository, never()).save(any());
        assert testBook.getShelves().size() == 2;
    }

    @Test
    void autoAddBookToKoboShelves_withNoKoboShelvesFound_shouldNotSave() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName()))
                .thenReturn(Collections.emptyList());

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(shelfRepository).findByUserIdInAndName(List.of(100L, 200L), ShelfType.KOBO.getName());
        verify(bookRepository, never()).save(any());
        assert testBook.getShelves().isEmpty();
    }

    @Test
    void autoAddBookToKoboShelves_withSingleUser_shouldAddToSingleShelf() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1));
        when(shelfRepository.findByUserIdInAndName(List.of(100L), ShelfType.KOBO.getName()))
                .thenReturn(List.of(koboShelf1));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).save(testBook);
        assert testBook.getShelves().contains(koboShelf1);
        assert testBook.getShelves().size() == 1;
    }

    @Test
    void autoAddBookToKoboShelves_withNullShelves_shouldInitializeAndAdd() {
        testBook = BookEntity.builder()
                .id(1L)
                .library(library)
                .shelves(null)
                .build();

        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue())
                .thenReturn(List.of(settings1));
        when(shelfRepository.findByUserIdInAndName(List.of(100L), ShelfType.KOBO.getName()))
                .thenReturn(List.of(koboShelf1));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        verify(bookRepository).save(testBook);
        assert testBook.getShelves() != null;
        assert testBook.getShelves().contains(koboShelf1);
        assert testBook.getShelves().size() == 1;
    }

    @Test
    void autoAddBookToKoboShelves_skipsUsersWithoutAccessToTheBooksLibrary() {
        testUser2.getLibraries().clear();
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue()).thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(anyList(), eq(ShelfType.KOBO.getName()))).thenReturn(List.of(koboShelf1, koboShelf2));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        assertThat(testBook.getShelves()).containsExactly(koboShelf1);
    }

    @Test
    void autoAddBookToKoboShelves_includesAdminsWhateverTheirLibraryAssignments() {
        testUser2.getLibraries().clear();
        testUser2.setPermissions(UserPermissionsEntity.builder().permissionAdmin(true).build());
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue()).thenReturn(List.of(settings2));
        when(shelfRepository.findByUserIdInAndName(anyList(), eq(ShelfType.KOBO.getName()))).thenReturn(List.of(koboShelf2));

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        assertThat(testBook.getShelves()).containsExactly(koboShelf2);
    }

    @Test
    void autoAddBookToKoboShelves_skipsUsersWhoseRestrictionsHideTheBook() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(testBook));
        when(koboCompatibilityService.isBookSupportedForKobo(testBook)).thenReturn(true);
        when(koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue()).thenReturn(List.of(settings1, settings2));
        when(shelfRepository.findByUserIdInAndName(anyList(), eq(ShelfType.KOBO.getName()))).thenReturn(List.of(koboShelf1, koboShelf2));
        when(contentRestrictionService.applyRestrictions(anyList(), eq(testUser2.getId()))).thenReturn(List.of());

        koboAutoShelfService.autoAddBookToKoboShelves(1L);

        assertThat(testBook.getShelves()).containsExactly(koboShelf1);
    }
}
