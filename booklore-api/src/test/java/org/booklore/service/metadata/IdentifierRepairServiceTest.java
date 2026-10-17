package org.booklore.service.metadata;

import org.booklore.model.dto.BookIdentifierRow;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.IdentifierConflictGroup;
import org.booklore.model.dto.IdentifierRepairPlan;
import org.booklore.model.dto.IdentifierRepairPlan.BookAction;
import org.booklore.model.dto.IdentifierRepairPlan.GroupStatus;
import org.booklore.model.dto.request.IdentifierRepairRequest;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.parser.AmazonBookParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentifierRepairServiceTest {

    @Mock private IdentifierConflictService identifierConflictService;
    @Mock private BookMetadataRepository bookMetadataRepository;
    @Mock private AmazonBookParser amazonBookParser;
    @Mock private AuditService auditService;
    @InjectMocks private IdentifierRepairService service;

    private static BookIdentifierRow row(long id, String title, String goodreadsId) {
        return new BookIdentifierRow(id, 1L, "Hayden", title, null, null, goodreadsId, "B0BJ7PJDQV", null, null);
    }

    private IdentifierRepairPlan runPlan() throws InterruptedException {
        service.startPlan();
        for (int i = 0; i < 100 && service.planStatus().running(); i++) {
            Thread.sleep(50);
        }
        return service.planStatus().plan();
    }

    @Test
    void keepsTheBookAmazonSaysOwnsTheAsinAndClearsTheRest() throws InterruptedException {
        when(identifierConflictService.findAllConflicts()).thenReturn(List.of(new IdentifierConflictGroup("B0BJ7PJDQV", 1L, "Hayden",
                List.of(row(5588, "Stand-In Groom", "62995403"), row(7623, "My Best Friend's Daughters: 3-Box Set", "77781586")))));
        when(amazonBookParser.lookupIdentifier("B0BJ7PJDQV")).thenReturn(BookMetadata.builder()
                .title("My Best Friend's Daughters").subtitle("3-Book Box Set").build());
        lenient().when(bookMetadataRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.of(BookMetadataEntity.builder().bookId(inv.getArgument(0)).asin("B0BJ7PJDQV").build()));

        IdentifierRepairPlan.Group group = runPlan().groups().getFirst();

        assertThat(group.status()).isEqualTo(GroupStatus.RESOLVED);
        assertThat(group.books()).extracting(IdentifierRepairPlan.BookPlan::action).containsExactly(BookAction.CLEAR, BookAction.KEEP);
    }

    @Test
    void changesNothingWhenAmazonDoesNotAnswer() throws InterruptedException {
        when(identifierConflictService.findAllConflicts()).thenReturn(List.of(new IdentifierConflictGroup("B0BJ7PJDQV", 1L, "Hayden",
                List.of(row(5588, "Stand-In Groom", "62995403"), row(7623, "My Best Friend's Daughters: 3-Box Set", "77781586")))));
        when(amazonBookParser.lookupIdentifier("B0BJ7PJDQV")).thenReturn(null);

        IdentifierRepairPlan.Group group = runPlan().groups().getFirst();

        assertThat(group.status()).isEqualTo(GroupStatus.LOOKUP_FAILED);
        assertThat(group.books()).allMatch(b -> b.action() == BookAction.KEEP);
    }

    @Test
    void applyClearsEveryUnlockedFieldHoldingTheIdentifier() {
        BookMetadataEntity running = BookMetadataEntity.builder().bookId(7682L).title("Running Blind")
                .asin("0515143502").isbn10("0515143502").isbn13("9780515143508").build();
        BookMetadataEntity locked = BookMetadataEntity.builder().bookId(1L).title("Locked").asin("0515143502").asinLocked(true).build();
        when(bookMetadataRepository.findById(7682L)).thenReturn(Optional.of(running));
        when(bookMetadataRepository.findById(1L)).thenReturn(Optional.of(locked));

        List<Long> changed = service.apply(new IdentifierRepairRequest(List.of(
                new IdentifierRepairRequest.Clear(7682L, "0515143502"),
                new IdentifierRepairRequest.Clear(1L, "0515143502"))));

        assertThat(changed).containsExactly(7682L);
        assertThat(running.getAsin()).isNull();
        assertThat(running.getIsbn10()).isNull();
        assertThat(running.getIsbn13()).isEqualTo("9780515143508");
        assertThat(locked.getAsin()).isEqualTo("0515143502");
    }
}
