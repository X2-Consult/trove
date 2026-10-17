package org.booklore.model.dto;

import java.util.List;

/**
 * What the shared-identifier repair would do, worked out without changing anything: for each
 * conflict group, which book Amazon says owns the identifier and which books it should be
 * cleared from (and then re-fetched).
 */
public record IdentifierRepairPlan(List<Group> groups) {

    public enum GroupStatus {
        /** Amazon's product matches one work in the group; the others lose the identifier. */
        RESOLVED,
        /** Amazon's product matches none of the books; all of them lose the identifier. */
        NO_OWNER,
        /** Amazon's product matches more than one work; left for a person to sort out. */
        AMBIGUOUS,
        /** Amazon didn't answer (blocked or no such product); nothing changes. Try again later. */
        LOOKUP_FAILED
    }

    public enum BookAction {KEEP, CLEAR, LOCKED}

    public record Group(String identifier, Long libraryId, String libraryName, String ownerTitle,
                        GroupStatus status, List<BookPlan> books) {
    }

    public record BookPlan(Long bookId, String title, String goodreadsId, BookAction action) {
    }
}
