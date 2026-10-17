package org.booklore.service.metadata;

import org.booklore.model.dto.BookIdentifierRow;
import org.booklore.model.dto.IdentifierConflictGroup;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentifierConflictServiceTest {

    @Mock private BookMetadataRepository bookMetadataRepository;
    @InjectMocks private IdentifierConflictService service;

    private static BookIdentifierRow row(long id, String title, String goodreadsId, String asin) {
        return new BookIdentifierRow(id, 1L, "Hayden", title, null, null, goodreadsId, asin, null, null);
    }

    @Test
    void sameWorkByGoodreadsIdOrTitle() {
        assertThat(IdentifierConflictService.isSameWork("60048804", "No Plan B: A Jack Reacher Novel", "60048804-no-plan-b", "No Plan B")).isTrue();
        assertThat(IdentifierConflictService.isSameWork(null, "No Plan B", "60048804", "No Plan B")).isTrue();
        // Same title, different books: the Goodreads IDs decide.
        assertThat(IdentifierConflictService.isSameWork("62214092", "Satisfying Her Needs", "61882497", "Satisfying Her Needs")).isFalse();
        assertThat(IdentifierConflictService.isSameWork(null, "Rock God: Part Two", null, "Rock God: Part Three")).isFalse();
    }

    @Test
    void reportsSharedAsinAcrossDifferentBooksOnly() {
        when(bookMetadataRepository.findIdentifierRows()).thenReturn(List.of(
                row(7426, "Rock God: Part Two", "142054116", "B0CJGL9NRB"),
                row(7466, "Rock God: Part Three", "199062185", "b0cjgl9nrb"),
                row(6311, "No Plan B: A Jack Reacher Novel", "60048804", "B09PMZ93DT"),
                row(7713, "No Plan B", "60048804", "B09PMZ93DT"),
                row(100, "Lonely Book", null, "B000000001")));

        List<IdentifierConflictGroup> groups = service.findAllConflicts();

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().identifier()).isEqualTo("B0CJGL9NRB");
        assertThat(groups.getFirst().books()).extracting(BookIdentifierRow::bookId).containsExactly(7426L, 7466L);
    }

    @Test
    void findConflictsIgnoresCopiesOfTheSameWork() {
        BookMetadataEntity mine = metadata(7426L, "Rock God: Part Two", "142054116");
        BookMetadataEntity sibling = metadata(7466L, "Rock God: Part Three", "199062185");
        BookMetadataEntity copy = metadata(9000L, "Rock God: Part Two", "142054116");
        when(bookMetadataRepository.findOthersInLibraryWithIdentifier(1L, 7426L, "B0CJGL9NRB")).thenReturn(List.of(sibling, copy));

        assertThat(service.findConflicts(mine, "b0cjgl9nrb")).containsExactly(sibling);
    }

    private static BookMetadataEntity metadata(long id, String title, String goodreadsId) {
        BookEntity book = BookEntity.builder().id(id).library(LibraryEntity.builder().id(1L).build()).build();
        BookMetadataEntity m = BookMetadataEntity.builder().bookId(id).title(title).goodreadsId(goodreadsId).build();
        m.setBook(book);
        return m;
    }
}
