-- The dry-run "find" tasks are now the first stage of their fix task, so their old runs and schedules go.
DELETE FROM tasks WHERE type IN ('FIND_EBOOK_ADS', 'FIND_EPUB_COVER_PROBLEMS');
DELETE FROM task_cron_configuration WHERE task_type IN ('FIND_EBOOK_ADS', 'FIND_EPUB_COVER_PROBLEMS');
