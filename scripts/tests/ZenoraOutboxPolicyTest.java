package org.telegram.messenger;

public final class ZenoraOutboxPolicyTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(ZenoraOutboxPolicy.isTransientHttpStatus(408), "408 must be retriable");
        check(ZenoraOutboxPolicy.isTransientHttpStatus(425), "425 must be retriable");
        check(ZenoraOutboxPolicy.isTransientHttpStatus(429), "429 must be retriable");
        check(ZenoraOutboxPolicy.isTransientHttpStatus(500), "5xx must be retriable");
        check(!ZenoraOutboxPolicy.isTransientHttpStatus(403), "403 must not be retriable");
        check(!ZenoraOutboxPolicy.isDefinitiveHttpStatus(401), "401 pauses for session recovery");
        check(ZenoraOutboxPolicy.isDefinitiveHttpStatus(403), "403 must become FAILED");
        check(ZenoraOutboxPolicy.isDefinitiveHttpStatus(404), "other permanent 4xx must become FAILED");

        long now = 1_000_000L;
        check(ZenoraOutboxPolicy.nextAttemptAt(now, 1, 1000) == now + 2_000L,
                "first retry delay must be two seconds");
        check(ZenoraOutboxPolicy.nextAttemptAt(now, 2, 1000) == now + 4_000L,
                "second retry delay must double");
        check(ZenoraOutboxPolicy.nextAttemptAt(now, 3, 1000) == now + 8_000L,
                "third retry delay must double");
        check(ZenoraOutboxPolicy.nextAttemptAt(now, 1, 750) == now + 1_500L,
                "negative jitter boundary");
        check(ZenoraOutboxPolicy.nextAttemptAt(now, 1, 1250) == now + 2_500L,
                "positive jitter boundary");
        check(ZenoraOutboxPolicy.nextAttemptAt(now, 30, 1000) == now + 300_000L,
                "retry delay must be capped");
        check(!ZenoraOutboxPolicy.shouldAutomaticallyRetry(
                ZenoraOutboxPolicy.UNKNOWN, now + 1, now), "future retry deadline must be respected");
        check(ZenoraOutboxPolicy.shouldAutomaticallyRetry(
                ZenoraOutboxPolicy.UNKNOWN, now, now), "due UNKNOWN rows must resume");
        check(!ZenoraOutboxPolicy.shouldAutomaticallyRetry(
                ZenoraOutboxPolicy.FAILED, 0, now), "FAILED rows require manual retry");
        System.out.println("Zenora outbox retry policy tests passed");
    }
}
