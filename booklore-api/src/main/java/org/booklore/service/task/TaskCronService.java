package org.booklore.service.task;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCronConfigRequest;
import org.booklore.model.dto.response.CronConfig;
import org.booklore.model.dto.response.CronPreview;
import org.booklore.model.entity.TaskCronConfigurationEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.repository.TaskCronConfigurationRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
@AllArgsConstructor
public class TaskCronService {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private final TaskCronConfigurationRepository repository;
    private final AuthenticationService authService;

    @Transactional(readOnly = true)
    public List<TaskCronConfigurationEntity> getAllEnabledCronConfigs() {
        return repository.findByEnabledTrue();
    }

    @Transactional(readOnly = true)
    public CronConfig getCronConfigOrDefault(TaskType taskType) {
        validateTaskTypeForCron(taskType);
        return repository.findByTaskType(taskType)
                .map(this::mapToResponse)
                .orElse(CronConfig.builder()
                        .taskType(taskType)
                        .enabled(false)
                        .build());
    }

    @Transactional
    public CronConfig patchCronConfig(TaskType taskType, TaskCronConfigRequest request) {
        validateTaskTypeForCron(taskType);
        BookLoreUser user = authService.getAuthenticatedUser();
        TaskCronConfigurationEntity config = repository.findByTaskType(taskType)
                .orElse(TaskCronConfigurationEntity.builder()
                        .taskType(taskType)
                        .createdBy(user.getId())
                        .enabled(false)
                        .build());
        if (request.getCronExpression() != null) {
            validateCronExpression(request.getCronExpression());
            config.setCronExpression(normalise(request.getCronExpression()));
        }
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        config = repository.save(config);
        log.info("Updated cron configuration for task type: {}", taskType);
        return mapToResponse(config);
    }

    private void validateTaskTypeForCron(TaskType taskType) {
        if (taskType == null) {
            throw new APIException("Task type is required", HttpStatus.BAD_REQUEST);
        }
        if (!taskType.isCronSupported()) {
            throw new APIException("Task type " + taskType + " does not support cron scheduling", HttpStatus.BAD_REQUEST);
        }
    }

    private static final int PREVIEW_RUNS = 5;

    /**
     * Trims the expression and turns a standard 5-field cron ("minute hour day month weekday") into
     * Spring's 6-field form by running at second 0. Macros such as {@code @daily} are left as they are.
     */
    public static String normalise(String cronExpression) {
        if (cronExpression == null) {
            return null;
        }
        String trimmed = String.join(" ", WHITESPACE_PATTERN.split(cronExpression.trim()));
        if (!trimmed.startsWith("@") && WHITESPACE_PATTERN.split(trimmed).length == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }

    /** Checks an expression the way the scheduler will read it and lists its next runs. */
    public CronPreview preview(String cronExpression) {
        String expression = normalise(cronExpression);
        ZoneId zone = ZoneId.systemDefault();
        try {
            CronExpression cron = parse(expression);
            List<OffsetDateTime> runs = new ArrayList<>();
            ZonedDateTime next = ZonedDateTime.now(zone);
            for (int i = 0; i < PREVIEW_RUNS && (next = cron.next(next)) != null; i++) {
                runs.add(next.toOffsetDateTime());
            }
            return CronPreview.builder().valid(true).expression(expression).nextRuns(runs).timeZone(zone.getId()).build();
        } catch (APIException e) {
            return CronPreview.builder().valid(false).expression(expression).error(e.getMessage()).nextRuns(List.of()).timeZone(zone.getId()).build();
        }
    }

    private void validateCronExpression(String cronExpression) {
        parse(normalise(cronExpression));
    }

    /** Spring's own parser, which also accepts L, W, #, names like MON or JAN, and macros. */
    private static CronExpression parse(String cronExpression) {
        if (cronExpression == null || cronExpression.isEmpty()) {
            throw new APIException("Cron expression is required", HttpStatus.BAD_REQUEST);
        }
        int fields = WHITESPACE_PATTERN.split(cronExpression).length;
        if (!cronExpression.startsWith("@") && fields != 6) {
            throw new APIException("Invalid cron expression format. Expected 6 fields (second minute hour day month day-of-week), "
                    + "or 5 for standard cron, but found " + fields, HttpStatus.BAD_REQUEST);
        }
        try {
            return CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException e) {
            throw new APIException("Invalid cron expression: " + cronExpression + ". " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private static OffsetDateTime nextRun(TaskCronConfigurationEntity config) {
        if (!Boolean.TRUE.equals(config.getEnabled()) || config.getCronExpression() == null) {
            return null;
        }
        try {
            ZonedDateTime next = CronExpression.parse(config.getCronExpression()).next(ZonedDateTime.now());
            return next != null ? next.toOffsetDateTime() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CronConfig mapToResponse(TaskCronConfigurationEntity config) {
        return CronConfig.builder()
                .id(config.getId())
                .taskType(config.getTaskType())
                .cronExpression(config.getCronExpression())
                .enabled(config.getEnabled())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .nextRun(nextRun(config))
                .build();
    }
}
