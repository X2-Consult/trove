package org.booklore.model.enums;

public enum BookFileCheckResult {
    /** Nothing to fix. */
    OK,
    /** Found a problem that hasn't been fixed, by a dry run or because the fix failed. */
    NEEDS_FIXING,
    /** Had a problem and it was fixed. */
    FIXED;

    /** Whether later runs can skip the file while it stays unchanged. */
    public boolean isSettled() {
        return this != NEEDS_FIXING;
    }
}
