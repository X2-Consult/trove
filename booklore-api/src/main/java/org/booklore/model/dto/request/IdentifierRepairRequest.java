package org.booklore.model.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** The identifiers to clear, as approved from a repair plan: one entry per book and identifier. */
public record IdentifierRepairRequest(@NotEmpty List<@Valid Clear> clears) {

    public record Clear(@NotNull Long bookId, @NotBlank String identifier) {
    }
}
