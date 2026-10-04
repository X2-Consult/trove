package org.booklore.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/** Whether a cron expression is valid, as Trove will store it, and when it would next run. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CronPreview {
    private boolean valid;
    private String expression;
    private String error;
    private List<OffsetDateTime> nextRuns;
    private String timeZone;
}
