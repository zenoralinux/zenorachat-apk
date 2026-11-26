package org.telegram.messenger;

/** Small deterministic policy helper for durable Zenora text retries. */
public final class ZenoraOutboxPolicy {
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    private static final long BASE_DELAY_MS = 2_000L;
    private static final long MAX_DELAY_MS = 5 * 60_000L;

    private ZenoraOutboxPolicy() {
    }

    public static boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    public static boolean isDefinitiveHttpStatus(int statusCode) {
        return statusCode >= 400 && statusCode < 500
                && statusCode != 401 && !isTransientHttpStatus(statusCode);
    }

    /** jitterPermille is clamped to [750, 1250], giving +/-25% bounded jitter. */
    public static long nextAttemptAt(long nowMs, int attemptCount, int jitterPermille) {
        int exponent = Math.max(0, Math.min(20, attemptCount - 1));
        long delay = BASE_DELAY_MS;
        for (int i = 0; i < exponent && delay < MAX_DELAY_MS; i++) {
            delay = Math.min(MAX_DELAY_MS, delay * 2L);
        }
        int jitter = Math.max(750, Math.min(1250, jitterPermille));
        return nowMs + (delay * jitter) / 1000L;
    }

    public static boolean shouldAutomaticallyRetry(String status, long nextAttemptAt, long nowMs) {
        return (UNKNOWN.equals(status) || SENDING.equals(status)) && nextAttemptAt <= nowMs;
    }
}
