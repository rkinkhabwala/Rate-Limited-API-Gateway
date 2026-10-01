package dev.practice.gateway.ratelimit;

/**
 * @param tokensRemaining requests left in the current burst
 * @param retryAfterMs    when rate-limited: how long until one token is back
 * @param quotaUsed       requests counted against this month's quota
 */
public record AdmissionDecision(
        Outcome outcome,
        long tokensRemaining,
        long retryAfterMs,
        long quotaUsed) {

    public enum Outcome {
        ALLOWED,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        /** Redis unreachable or errored: the filter applies the fail-open/fail-closed policy. */
        UNAVAILABLE
    }

    public static AdmissionDecision unavailable() {
        return new AdmissionDecision(Outcome.UNAVAILABLE, 0, 0, 0);
    }
}
