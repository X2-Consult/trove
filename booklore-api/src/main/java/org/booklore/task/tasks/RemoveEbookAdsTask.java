package org.booklore.task.tasks;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.TaskType;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.file.EbookAdCleanerService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Component;

/**
 * Removes the OceanofPDF advert from every EPUB that has it, replacing the file in place and keeping
 * the original as a backup. Each cleaned book gets an audit log entry naming its backup.
 */
@Component
@Slf4j
public class RemoveEbookAdsTask extends EbookAdTask {

    private final AuditService auditService;
    private final AppProperties appProperties;

    public RemoveEbookAdsTask(EbookAdCleanerService cleanerService, NotificationService notificationService,
                              TaskCancellationManager cancellationManager, AuditService auditService, AppProperties appProperties) {
        super(cleanerService, notificationService, cancellationManager);
        this.auditService = auditService;
        this.appProperties = appProperties;
    }

    @Override
    protected void checkCanRun() {
        if (!appProperties.isLocalStorage()) {
            throw new IllegalStateException("Book files can't be changed with DISK_TYPE=" + appProperties.getDiskType());
        }
    }

    @Override
    protected void process(BookFileEntity bookFile, Tally tally) throws Exception {
        EbookAdCleanerService.Cleaned cleaned = cleanerService.clean(bookFile);
        if (cleaned == null) {
            return;
        }
        tally.books++;
        tally.blocks += cleaned.findings().adBlocks();
        tally.strayFiles += cleaned.findings().strayFile() ? 1 : 0;
        tally.otherMentions += cleaned.findings().otherMentions();
        Long bookId = bookFile.getBook().getId();
        String path = describePath(bookFile);
        log.info("{}: Removed {} OceanofPDF link(s) from bookId={} path={} (original kept at {})",
                getTaskType(), cleaned.findings().adBlocks(), bookId, path, cleaned.backup());
        auditService.log(AuditAction.EBOOK_ADS_REMOVED, "Book", bookId,
                "Removed " + plural(cleaned.findings().adBlocks(), "OceanofPDF link")
                        + (cleaned.findings().strayFile() ? " and the stray oceanofpdf.com file" : "")
                        + " from " + path + "; original kept at " + cleaned.backup());
    }

    @Override
    protected String summary(Tally tally) {
        String text = String.format("Removed OceanofPDF ads from %s of %d EPUBs (%s, %s). Originals are kept in the ad-cleaner-backups folder.",
                plural(tally.books, "book"), tally.checked, plural(tally.blocks, "link"), plural(tally.strayFiles, "stray file"));
        if (tally.otherMentions > 0) {
            text += " " + plural(tally.otherMentions, "other mention") + " of OceanofPDF were left in.";
        }
        if (tally.failed > 0) {
            text += " " + plural(tally.failed, "file") + " couldn't be cleaned; see Settings > Server Logs.";
        }
        return text;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.REMOVE_EBOOK_ADS;
    }
}
