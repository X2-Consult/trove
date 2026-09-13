package org.booklore.service.kobo;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.KoboUserSettingsEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.KoboUserSettingsRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.restriction.ContentRestrictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoboAutoShelfService {

    private final KoboUserSettingsRepository koboUserSettingsRepository;
    private final ShelfRepository shelfRepository;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final KoboCompatibilityService koboCompatibilityService;
    private final ContentRestrictionService contentRestrictionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoAddBookToKoboShelves(Long bookId) {
        if (bookId == null) {
            log.warn("Book ID is null for auto-add to Kobo shelf");
            return;
        }

        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (!isBookEligible(book)) {
            return;
        }

        List<KoboUserSettingsEntity> eligibleUsers = koboUserSettingsRepository.findByAutoAddToShelfTrueAndSyncEnabledTrue();

        if (eligibleUsers.isEmpty()) {
            log.debug("No Kobo auto-add enabled users for book {}", book.getId());
            return;
        }

        List<Long> userIds = eligibleUsers.stream()
                .map(KoboUserSettingsEntity::getUserId)
                .toList();

        Map<Long, BookLoreUserEntity> users = userRepository.findAllById(userIds)
                .stream()
                .collect(Collectors.toMap(BookLoreUserEntity::getId, u -> u));

        List<ShelfEntity> shelves = shelfRepository.findByUserIdInAndName(userIds, ShelfType.KOBO.getName());

        Map<Long, ShelfEntity> shelfByUser = shelves
                .stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s));

        boolean modified = false;

        for (KoboUserSettingsEntity setting : eligibleUsers) {
            ShelfEntity shelf = shelfByUser.get(setting.getUserId());

            if (shelf == null) {
                log.debug("User {} has auto-add enabled but no Kobo shelf exists", setting.getUserId());
                continue;
            }

            // Auto-add used to put every new book on every Kobo user's shelf, so books from libraries a
            // user can't access synced to their device (Grimmory 4980391c). Their content restrictions
            // count too: a book they can't see in the app mustn't reach their Kobo.
            if (!canSeeBook(users.get(setting.getUserId()), book)) {
                log.debug("Book {} is not one user {} can see", book.getId(), setting.getUserId());
                continue;
            }

            if (book.getShelves() == null) {
                book.setShelves(new HashSet<>());
            }

            if (book.getShelves().contains(shelf)) {
                log.debug("Book {} already on Kobo shelf for user {}", book.getId(), setting.getUserId());
                continue;
            }

            book.getShelves().add(shelf);
            modified = true;
            log.info("Auto-added book {} to Kobo shelf for user {}", book.getId(), setting.getUserId());
        }

        if (modified) {
            bookRepository.save(book);
        }
    }

    private boolean canSeeBook(BookLoreUserEntity user, BookEntity book) {
        if (user == null || book.getLibrary() == null) {
            return false;
        }
        UserPermissionsEntity permissions = user.getPermissions();
        if (permissions != null && permissions.isPermissionAdmin()) {
            return true;
        }
        Long libraryId = book.getLibrary().getId();
        boolean inTheirLibraries = user.getLibraries() != null
                && user.getLibraries().stream().anyMatch(library -> library.getId().equals(libraryId));
        return inTheirLibraries && !contentRestrictionService.applyRestrictions(List.of(book), user.getId()).isEmpty();
    }

    private boolean isBookEligible(BookEntity book) {
        if (book == null) {
            log.warn("Book not found for Kobo auto-add");
            return false;
        }

        if (!koboCompatibilityService.isBookSupportedForKobo(book)) {
            log.debug("Book {} is not Kobo-compatible, skipping", book.getId());
            return false;
        }

        return true;
    }
}
