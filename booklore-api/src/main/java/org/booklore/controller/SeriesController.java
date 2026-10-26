package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.service.series.SeriesGrouping;
import org.booklore.service.series.SeriesService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/series")
@Tag(name = "Series", description = "The library's series, and putting same-name books into one series by hand")
public class SeriesController {

    private final SeriesService seriesService;
    private final AuthenticationService authenticationService;

    public record BookIdsRequest(List<Long> bookIds) {
    }

    @Operation(summary = "List series", description = "Every series of the books you can see, with a key that stays the same while the series does "
            + "(gr:<Goodreads series ID>, amzn:<series ASIN>, manual:<series group> or name:<name>|<author>) and a display name that adds the author where two series share a name.")
    @ApiResponse(responseCode = "200", description = "Series returned")
    @GetMapping
    public ResponseEntity<List<SeriesGrouping.Group>> getSeries() {
        return ResponseEntity.ok(seriesService.getSeries(authenticationService.getAuthenticatedUser()));
    }

    @Operation(summary = "Put books in one series", description = "Makes these books, which must share a series name, one series of their own. "
            + "Used both to split books off a series and to merge two series.")
    @ApiResponse(responseCode = "200", description = "The new series group")
    @PostMapping("/group")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public ResponseEntity<Map<String, String>> group(@RequestBody BookIdsRequest request) {
        String group = seriesService.groupTogether(authenticationService.getAuthenticatedUser(), request.bookIds());
        return ResponseEntity.ok(Map.of("seriesGroup", group));
    }

    @Operation(summary = "Undo putting books in one series", description = "Lets these books' series be worked out from their series IDs and authors again.")
    @ApiResponse(responseCode = "204", description = "Cleared")
    @PostMapping("/ungroup")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public ResponseEntity<Void> ungroup(@RequestBody BookIdsRequest request) {
        seriesService.ungroup(authenticationService.getAuthenticatedUser(), request.bookIds());
        return ResponseEntity.noContent().build();
    }
}
