package dev.vaultdown.core;

/** Monotonic deadline and server-directed OAuth device polling backoff. */
public final class DevicePoll {
    private final long deadline;
    private long intervalSeconds;
    public DevicePoll(long nowMillis, long expiresSeconds, long intervalSeconds) {
        if (expiresSeconds <= 0 || expiresSeconds > 3600 || intervalSeconds < 0 || intervalSeconds > 3600)
            throw new IllegalArgumentException("Invalid GitHub sign-in lifetime.");
        deadline = nowMillis + expiresSeconds * 1000;
        this.intervalSeconds = Math.max(5, intervalSeconds);
    }
    public long delayMillis() { return intervalSeconds * 1000; }
    public void checkActive(long nowMillis) {
        if (nowMillis >= deadline) throw new IllegalStateException("Sign-in code expired. Sign in again for a new code.");
    }
    public void accept(String error, long reportedInterval) {
        switch (error) {
            case "authorization_pending": return;
            case "slow_down": intervalSeconds = Math.max(intervalSeconds + 5, Math.min(3600, reportedInterval)); return;
            case "access_denied": throw new IllegalStateException("GitHub sign-in was cancelled. You can try again.");
            case "expired_token": case "token_expired": throw new IllegalStateException("Sign-in code expired. Sign in again for a new code.");
            default: throw new IllegalStateException("GitHub could not complete sign-in. Please try again.");
        }
    }
}
