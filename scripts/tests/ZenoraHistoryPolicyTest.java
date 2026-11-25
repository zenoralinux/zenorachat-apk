package org.telegram.messenger;

public final class ZenoraHistoryPolicyTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(ZenoraHistoryPolicy.mergeOlderCursor(0, 50) == 50, "initial cursor");
        check(ZenoraHistoryPolicy.mergeOlderCursor(40, 50) == 40,
                "new initial page must not skip an older saved cursor");
        check(ZenoraHistoryPolicy.mergeOlderCursor(50, 40) == 40,
                "older page must advance the cursor backwards");
        check(ZenoraHistoryPolicy.mergeOlderCursor(0, 0) == 0, "empty cursor");
        check(ZenoraHistoryPolicy.canRequestOlder(-1, 40),
                "unknown coverage with a cursor must remain retryable");
        check(ZenoraHistoryPolicy.canRequestOlder(1, 40), "has-more cursor must be retryable");
        check(!ZenoraHistoryPolicy.canRequestOlder(0, 40), "confirmed end is terminal");
        check(!ZenoraHistoryPolicy.canRequestOlder(1, 0), "cursorless state cannot be paged");
        check(ZenoraHistoryPolicy.reachedLocalCacheBoundary(0, 50), "empty local page boundary");
        check(ZenoraHistoryPolicy.reachedLocalCacheBoundary(49, 50), "short local page boundary");
        check(!ZenoraHistoryPolicy.reachedLocalCacheBoundary(50, 50), "full local page is not a boundary");
        check(ZenoraHistoryPolicy.isAuthoritativeEnd(0, true), "valid server end");
        check(!ZenoraHistoryPolicy.isAuthoritativeEnd(0, false), "unknown coverage is not server end");
        check(!ZenoraHistoryPolicy.isAuthoritativeEnd(-1, true), "unknown server flag is not end");
        check(ZenoraHistoryPolicy.isLocalCoverageIntact(100, 100), "unchanged local oldest bound");
        check(ZenoraHistoryPolicy.isLocalCoverageIntact(100, 50), "additional older cache rows are intact");
        check(!ZenoraHistoryPolicy.isLocalCoverageIntact(100, 120), "pruned older cache rows invalidate coverage");
        check(!ZenoraHistoryPolicy.isLocalCoverageIntact(100, 0), "empty cache invalidates previous positive coverage");
        check(ZenoraHistoryPolicy.isLocalCoverageIntact(0, 120), "new cache with no previous bound is unknown, not pruned");
        System.out.println("Zenora history pagination policy tests passed");
    }
}
