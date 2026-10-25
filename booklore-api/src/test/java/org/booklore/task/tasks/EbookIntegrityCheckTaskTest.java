package org.booklore.task.tasks;

import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.file.BookFileCheckService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EbookIntegrityCheckTaskTest {

    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private BookFileCheckService checks;
    @Mock
    private NotificationService notificationService;
    @Mock
    private TaskCancellationManager cancellationManager;

    @InjectMocks
    private EbookIntegrityCheckTask task;

    @TempDir
    Path dir;

    @Test
    void recordsPassingAndFailingFilesThenSkipsOnlyUnchangedPassedOnes() throws IOException {
        BookFileEntity good = bookFile(1L, "good.epub");
        writeEpub(dir.resolve("good.epub"));
        BookFileEntity bad = bookFile(2L, "bad.epub");
        Files.writeString(dir.resolve("bad.epub"), "not a zip");
        when(bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(anyList())).thenReturn(List.of(good, bad));
        when(checks.settled(BookFileCheckType.INTEGRITY)).thenReturn(new BookFileCheckService.Settled(Map.of()));

        var response = task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(checks).record(eq(BookFileCheckType.INTEGRITY), eq(1L), state.capture(), eq(BookFileCheckResult.OK));
        verify(checks).record(eq(BookFileCheckType.INTEGRITY), eq(2L), anyString(), eq(BookFileCheckResult.NEEDS_FIXING));
        verify(auditService, times(1)).log(any(), any(), eq(2L), anyString());

        // Second run: the good file is settled at the state it was checked at, so it isn't opened or recorded again.
        clearInvocations(checks, auditService);
        when(checks.settled(BookFileCheckType.INTEGRITY))
                .thenReturn(new BookFileCheckService.Settled(Map.of(1L, state.getValue())));

        task.execute(TaskCreateRequest.builder().taskId("t2").build());

        verify(checks, never()).record(any(), eq(1L), any(), any());
        verify(auditService, times(1)).log(any(), any(), eq(2L), anyString());
        assertThat(task.getMetadata()).contains("1 already checked and unchanged");
    }

    @Test
    void checksAgainWhenTheFileChangesOnDisk() throws IOException {
        BookFileEntity good = bookFile(1L, "good.epub");
        writeEpub(dir.resolve("good.epub"));
        when(bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(anyList())).thenReturn(List.of(good));
        when(checks.settled(BookFileCheckType.INTEGRITY))
                .thenReturn(new BookFileCheckService.Settled(Map.of(1L, "hash-1:1:1")));

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        verify(checks).record(eq(BookFileCheckType.INTEGRITY), eq(1L), anyString(), eq(BookFileCheckResult.OK));
    }

    private BookFileEntity bookFile(Long id, String name) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(dir.toString());
        BookEntity book = new BookEntity();
        book.setId(id);
        book.setLibraryPath(libraryPath);
        BookFileEntity file = new BookFileEntity();
        file.setId(id);
        file.setBook(book);
        file.setFileSubPath("");
        file.setFileName(name);
        file.setBookType(BookFileType.EPUB);
        file.setCurrentHash("hash-" + id);
        return file;
    }

    private static void writeEpub(Path path) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes());
            zip.closeEntry();
        }
    }
}
