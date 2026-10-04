package org.booklore.task.tasks;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.service.NotificationService;
import org.booklore.service.file.EbookAdCleanerService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.util.EpubAdCleaner;
import org.springframework.stereotype.Component;

/** Dry run: logs every EPUB carrying the OceanofPDF advert and how many links it has. Changes nothing. */
@Component
@Slf4j
public class FindEbookAdsTask extends EbookAdTask {

    public FindEbookAdsTask(EbookAdCleanerService cleanerService, NotificationService notificationService, TaskCancellationManager cancellationManager) {
        super(cleanerService, notificationService, cancellationManager);
    }

    @Override
    protected void process(BookFileEntity bookFile, Tally tally) throws Exception {
        EpubAdCleaner.Findings findings = cleanerService.scan(bookFile);
        tally.otherMentions += findings.otherMentions();
        if (!findings.hasAds()) {
            return;
        }
        tally.books++;
        tally.blocks += findings.adBlocks();
        tally.strayFiles += findings.strayFile() ? 1 : 0;
        log.info("{}: bookId={} has {} OceanofPDF link(s){}{}: {}", getTaskType(), bookFile.getBook().getId(),
                findings.adBlocks(), findings.strayFile() ? " and the stray oceanofpdf.com file" : "",
                findings.otherMentions() > 0 ? ", plus " + findings.otherMentions() + " other mention(s) that won't be removed" : "",
                describePath(bookFile));
    }

    @Override
    protected String summary(Tally tally) {
        String text = String.format("%s of %d EPUBs carry OceanofPDF ads (%s, %s). Nothing was changed.",
                plural(tally.books, "book"), tally.checked, plural(tally.blocks, "link"), plural(tally.strayFiles, "stray file"));
        if (tally.otherMentions > 0) {
            text += " " + plural(tally.otherMentions, "other mention") + " of OceanofPDF won't be removed.";
        }
        if (tally.failed > 0) {
            text += " " + plural(tally.failed, "file") + " couldn't be read.";
        }
        return text + " The books are listed in the server log.";
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.FIND_EBOOK_ADS;
    }
}
