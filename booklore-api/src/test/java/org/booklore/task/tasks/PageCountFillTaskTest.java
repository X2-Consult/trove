package org.booklore.task.tasks;

import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.PageCountService;
import org.booklore.task.TaskStatus;
import org.booklore.util.PageCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PageCountFillTaskTest {

    @Mock
    private BookMetadataRepository bookMetadataRepository;
    @Mock
    private BookAdditionalFileRepository bookFileRepository;
    @Mock
    private PageCountService pageCountService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private PageCountFillTask task;

    private final List<BookFileEntity> files = List.of(new BookFileEntity());

    @Test
    void measuresWordsPerPageThenFillsAndMarksEstimates() {
        List<Long> known = LongStream.rangeClosed(100, 124).boxed().toList();
        when(bookMetadataRepository.findBookIdsMissingPageCount()).thenReturn(List.of(1L, 2L, 3L));
        when(bookMetadataRepository.findBookIdsWithKnownPageCountAndText()).thenReturn(known);
        when(bookFileRepository.findByBookIdAndIsBookFormat(anyLong(), eq(true))).thenReturn(files);
        when(bookMetadataRepository.findPageCount(anyLong())).thenReturn(100);
        when(pageCountService.countWords(files)).thenReturn(30_000L);   // 300 words a page in this library
        when(pageCountService.count(files, 300)).thenReturn(
                Optional.of(new PageCounter.Result(12, PageCounter.Source.PDF_PAGES)),
                Optional.of(new PageCounter.Result(90, PageCounter.Source.WORD_COUNT)),
                Optional.empty());
        when(bookMetadataRepository.fillMissingPageCount(anyLong(), anyInt(), anyBoolean())).thenReturn(1);

        var response = task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(bookMetadataRepository).fillMissingPageCount(1L, 12, false);
        verify(bookMetadataRepository).fillMissingPageCount(2L, 90, true);
        verify(bookMetadataRepository, never()).fillMissingPageCount(eq(3L), anyInt(), anyBoolean());
    }

    @Test
    void fallsBackToAPaperbackPageWithTooFewBooksToMeasure() {
        when(bookMetadataRepository.findBookIdsMissingPageCount()).thenReturn(List.of(1L));
        when(bookMetadataRepository.findBookIdsWithKnownPageCountAndText()).thenReturn(List.of());
        when(bookFileRepository.findByBookIdAndIsBookFormat(1L, true)).thenReturn(files);
        when(pageCountService.count(files, PageCounter.WORDS_PER_PAGE)).thenReturn(Optional.of(new PageCounter.Result(40, PageCounter.Source.WORD_COUNT)));
        when(bookMetadataRepository.fillMissingPageCount(1L, 40, true)).thenReturn(1);

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        verify(bookMetadataRepository).fillMissingPageCount(1L, 40, true);
    }

    @Test
    void nothingToDoWhenEveryBookHasAPageCount() {
        when(bookMetadataRepository.findBookIdsMissingPageCount()).thenReturn(List.of());

        assertThat(task.execute(TaskCreateRequest.builder().taskId("t").build()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verifyNoInteractions(pageCountService);
    }

    @Test
    void theSampleIsSpreadThroughTheLibrary() {
        List<Integer> items = java.util.stream.IntStream.range(0, 1000).boxed().toList();

        assertThat(PageCountFillTask.spread(items, 4)).containsExactly(0, 250, 500, 750);
        assertThat(PageCountFillTask.spread(List.of(1, 2), 4)).containsExactly(1, 2);
    }

    @Test
    void theSummarySaysWhereTheCountsCameFrom() {
        String summary = PageCountFillTask.summary(10,
                Map.of(PageCounter.Source.PDF_PAGES, 2, PageCounter.Source.PRINT_PAGE_NUMBERS, 3, PageCounter.Source.WORD_COUNT, 4), 1,
                new PageCountFillTask.Calibration(262, 180));

        assertThat(summary).isEqualTo("Filled page counts for 9 of 10 books: 2 counted exactly (PDF, comic, fixed layout), "
                + "3 from the print edition's page numbers, 4 estimated from word count at 262 words per page, "
                + "measured from 180 of your books. 1 couldn't be counted (MOBI or AZW3 only, or the file couldn't be read).");
    }
}
