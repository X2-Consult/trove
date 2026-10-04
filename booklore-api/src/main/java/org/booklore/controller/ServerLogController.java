package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.service.logs.ServerLogService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/server-logs")
@Tag(name = "Server Logs", description = "Endpoints for reading Trove's log file")
public class ServerLogController {

    private final ServerLogService serverLogService;

    @Operation(summary = "List log files", description = "The current log file and its rotated archives. Requires admin.")
    @GetMapping("/files")
    @PreAuthorize("@securityUtil.isAdmin()")
    public List<ServerLogService.LogFile> files() throws IOException {
        return serverLogService.files();
    }

    @Operation(summary = "Read the log", description = "The newest matching entries of a log file, newest first. Requires admin.")
    @GetMapping
    @PreAuthorize("@securityUtil.isAdmin()")
    public ResponseEntity<ServerLogService.LogPage> read(
            @Parameter(description = "Log file name from /files; the current file when empty") @RequestParam(required = false) String file,
            @Parameter(description = "Lowest level to include: DEBUG, INFO, WARN or ERROR") @RequestParam(required = false) String level,
            @Parameter(description = "Text the entry must contain") @RequestParam(required = false) String q,
            @Parameter(description = "Maximum entries to return") @RequestParam(defaultValue = "500") int limit) throws IOException {
        try {
            return ResponseEntity.ok(serverLogService.read(file, level, q, limit));
        } catch (NoSuchFileException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(summary = "Download a log file", description = "Requires admin.")
    @GetMapping("/download")
    @PreAuthorize("@securityUtil.isAdmin()")
    public ResponseEntity<Resource> download(@RequestParam(required = false) String file) throws IOException {
        Path path;
        try {
            path = serverLogService.resolve(file);
        } catch (NoSuchFileException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(path.getFileName().toString()).build().toString())
                .contentType(path.toString().endsWith(".gz") ? MediaType.parseMediaType("application/gzip") : MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(path));
    }
}
