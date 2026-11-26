package org.telegram.messenger;

/** Deterministic rules for durable Zenora history pagination. */
public final class ZenoraHistoryPolicy {
    private ZenoraHistoryPolicy() {
    }

    /** Keep the oldest positive cursor so a newer refresh never skips cached older pages. */
    public static long mergeOlderCursor(long currentCursor, long proposedCursor) {
        if (currentCursor <= 0) return Math.max(0, proposedCursor);
        if (proposedCursor <= 0) return currentCursor;
        return Math.min(currentCursor, proposedCursor);
    }

    /** -1 means unknown: continue probing from a valid cursor rather than claiming end-of-history. */
    public static boolean canRequestOlder(int hasMoreOlder, long cursor) {
        return cursor > 0 && hasMoreOlder != 0;
    }

    public static boolean reachedLocalCacheBoundary(int loadedCount, int requestedCount) {
        return requestedCount > 0 && loadedCount < requestedCount;
    }

    public static boolean isAuthoritativeEnd(int hasMoreOlder, boolean coverageValid) {
        return coverageValid && hasMoreOlder == 0;
    }

    /** Detect whether cached older rows disappeared since the durable bounds were recorded. */
    public static boolean isLocalCoverageIntact(long persistedOldestLocalId, long currentOldestLocalId) {
        if (persistedOldestLocalId <= 0L) return true;
        return currentOldestLocalId > 0L && currentOldestLocalId <= persistedOldestLocalId;
    }
}
