package org.booklore.service.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookIdentifierRow;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.IdentifierConflictGroup;
import org.booklore.model.dto.IdentifierRepairPlan;
import org.booklore.model.dto.IdentifierRepairPlan.BookAction;
import org.booklore.model.dto.IdentifierRepairPlan.BookPlan;
import org.booklore.model.dto.IdentifierRepairPlan.GroupStatus;
import org.booklore.model.dto.request.IdentifierRepairRequest;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.parser.AmazonBookParser;
import org.booklore.service.metadata.parser.TitleMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Repairs ASINs/ISBNs copied onto the wrong books. The plan asks Amazon which product each shared
 * identifier is, keeps it on the book(s) that product is, and marks the rest for clearing; the
 * caller then re-fetches the cleared books so they pick up their own identifiers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentifierRepairService {

    private final IdentifierConflictService identifierConflictService;
    private final BookMetadataRepository bookMetadataRepository;
    private final AmazonBookParser amazonBookParser;
    private final AuditService auditService;

    /** Progress of the background dry run; {@code plan} is set once it has finished. */
    public record PlanStatus(boolean running, int checked, int total, IdentifierRepairPlan plan, String error) {
    }

    private volatile PlanStatus planStatus = new PlanStatus(false, 0, 0, null, null);

    /**
     * Starts working out the repair in the background, without writing anything. It makes one
     * (paced) Amazon request per conflict group, which can outlast a proxied HTTP request, so
     * callers poll {@link #planStatus()}. A dry run already in progress is left to finish.
     */
    public synchronized PlanStatus startPlan() {
        if (planStatus.running()) {
            return planStatus;
        }
        List<IdentifierConflictGroup> conflicts = identifierConflictService.findAllConflicts();
        planStatus = new PlanStatus(true, 0, conflicts.size(), null, null);
        Thread.ofVirtual().name("identifier-repair-plan").start(() -> {
            try {
                List<IdentifierRepairPlan.Group> groups = new ArrayList<>();
                for (IdentifierConflictGroup group : conflicts) {
                    groups.add(planGroup(group));
                    planStatus = new PlanStatus(true, groups.size(), conflicts.size(), null, null);
                }
                planStatus = new PlanStatus(false, groups.size(), conflicts.size(), new IdentifierRepairPlan(groups), null);
            } catch (Exception e) {
                log.error("Identifier repair: dry run failed", e);
                planStatus = new PlanStatus(false, planStatus.checked(), conflicts.size(), null, e.getMessage());
            }
        });
        return planStatus;
    }

    public PlanStatus planStatus() {
        return planStatus;
    }

    private IdentifierRepairPlan.Group planGroup(IdentifierConflictGroup group) {
        BookMetadata product;
        try {
            product = amazonBookParser.lookupIdentifier(group.identifier());
        } catch (Exception e) {
            log.warn("Identifier repair: Amazon lookup of {} failed: {}", group.identifier(), e.getMessage());
            product = null;
        }
        if (product == null) {
            return group(group, null, GroupStatus.LOOKUP_FAILED, book -> BookAction.KEEP);
        }

        String ownerTitle = TitleMatcher.fullTitle(product.getTitle(), product.getSubtitle());
        List<BookIdentifierRow> owners = group.books().stream()
                .filter(book -> book.title() != null && TitleMatcher.matches(book.title(), ownerTitle))
                .toList();
        if (owners.isEmpty()) {
            return group(group, ownerTitle, GroupStatus.NO_OWNER, book -> clearAction(book, group.identifier()));
        }
        BookIdentifierRow first = owners.getFirst();
        boolean oneWork = owners.stream().allMatch(o ->
                IdentifierConflictService.isSameWork(first.goodreadsId(), first.title(), o.goodreadsId(), o.title()));
        if (!oneWork) {
            return group(group, ownerTitle, GroupStatus.AMBIGUOUS, book -> BookAction.KEEP);
        }
        Set<Long> ownerIds = new HashSet<>();
        owners.forEach(o -> ownerIds.add(o.bookId()));
        return group(group, ownerTitle, GroupStatus.RESOLVED,
                book -> ownerIds.contains(book.bookId()) ? BookAction.KEEP : clearAction(book, group.identifier()));
    }

    private BookAction clearAction(BookIdentifierRow book, String identifier) {
        return bookMetadataRepository.findById(book.bookId())
                .map(m -> heldFields(m, identifier).stream().allMatch(f -> f.locked) ? BookAction.LOCKED : BookAction.CLEAR)
                .orElse(BookAction.KEEP);
    }

    private static IdentifierRepairPlan.Group group(IdentifierConflictGroup group, String ownerTitle, GroupStatus status,
                                                    java.util.function.Function<BookIdentifierRow, BookAction> action) {
        List<BookPlan> books = group.books().stream()
                .map(b -> new BookPlan(b.bookId(), b.title(), b.goodreadsId(), action.apply(b)))
                .toList();
        return new IdentifierRepairPlan.Group(group.identifier(), group.libraryId(), group.libraryName(), ownerTitle, status, books);
    }

    /**
     * Clears each approved identifier from its book, in whichever of ASIN/ISBN-13/ISBN-10 holds
     * it, skipping locked fields. Returns the IDs of the books that changed, to be re-fetched.
     */
    @Transactional
    public List<Long> apply(IdentifierRepairRequest request) {
        Set<Long> changed = new LinkedHashSet<>();
        for (IdentifierRepairRequest.Clear clear : request.clears()) {
            BookMetadataEntity metadata = bookMetadataRepository.findById(clear.bookId()).orElse(null);
            if (metadata == null) continue;
            List<String> cleared = new ArrayList<>();
            for (HeldField field : heldFields(metadata, clear.identifier())) {
                if (field.locked) continue;
                field.clear.run();
                cleared.add(field.label);
            }
            if (!cleared.isEmpty()) {
                changed.add(clear.bookId());
                log.info("Identifier repair: cleared {} {} from book {} \"{}\"", String.join("/", cleared), clear.identifier(),
                        clear.bookId(), metadata.getTitle());
                auditService.log(AuditAction.METADATA_UPDATED, "Book", clear.bookId(),
                        "Cleared shared " + String.join("/", cleared) + " " + clear.identifier() + " from: " + metadata.getTitle());
            }
        }
        return new ArrayList<>(changed);
    }

    private record HeldField(String label, boolean locked, Runnable clear) {
    }

    private static List<HeldField> heldFields(BookMetadataEntity m, String identifier) {
        String id = identifier.strip();
        List<HeldField> fields = new ArrayList<>();
        if (id.equalsIgnoreCase(Objects.toString(m.getAsin(), "").strip())) {
            fields.add(new HeldField("ASIN", Boolean.TRUE.equals(m.getAsinLocked()), () -> m.setAsin(null)));
        }
        if (id.equalsIgnoreCase(Objects.toString(m.getIsbn13(), "").strip())) {
            fields.add(new HeldField("ISBN-13", Boolean.TRUE.equals(m.getIsbn13Locked()), () -> m.setIsbn13(null)));
        }
        if (id.equalsIgnoreCase(Objects.toString(m.getIsbn10(), "").strip())) {
            fields.add(new HeldField("ISBN-10", Boolean.TRUE.equals(m.getIsbn10Locked()), () -> m.setIsbn10(null)));
        }
        return fields;
    }
}
