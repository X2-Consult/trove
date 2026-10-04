package org.booklore.service.metadata.writer;

/**
 * Whether a new cover made it into the book file. {@link Status#SKIPPED} covers the cases where Trove
 * isn't meant to write it (saving to files is off, the file's too large, or the format has no cover).
 */
public record CoverWriteResult(Status status, String message) {

    public enum Status {
        WRITTEN,
        SKIPPED,
        FAILED
    }

    public static CoverWriteResult written(String message) {
        return new CoverWriteResult(Status.WRITTEN, message);
    }

    public static CoverWriteResult skipped(String message) {
        return new CoverWriteResult(Status.SKIPPED, message);
    }

    public static CoverWriteResult failed(String message) {
        return new CoverWriteResult(Status.FAILED, message);
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }
}
