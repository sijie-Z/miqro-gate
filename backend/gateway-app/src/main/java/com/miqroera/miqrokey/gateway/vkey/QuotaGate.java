package com.miqroera.miqrokey.gateway.vkey;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Quota soft-landing gate (#684, ADR-0020): when an exceeded REJECT quota rule
 * covers the request's user or project, the gateway answers {@code 429
 * quota_exceeded} until the window rolls over or the admin raises the limit.
 * The verdict comes from the route snapshot (control-plane evaluated) — the hot
 * path only reads an in-memory set, never a database.
 */
public final class QuotaGate {

    private QuotaGate() {
    }

    public static void requireNotExceeded(AuthContext ctx) {
        Instant userUntil = ctx.snapshot().quotaBlockedUserUntil(ctx.key().userId());
        if (userUntil != null) {
            throw blocked("user", userUntil);
        }
        Instant projectUntil = ctx.snapshot().quotaBlockedProjectUntil(ctx.binding().projectId());
        if (projectUntil != null) {
            throw blocked("project", projectUntil);
        }
    }

    private static AuthFailureException blocked(String scope, Instant until) {
        return new AuthFailureException(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded",
                "The usage quota for this " + scope + " has been exceeded; requests are rejected until the quota"
                        + " resets or the limit is raised",
                retryAfterSeconds(until));
    }

    /**
     * Seconds until the window rolls over (never below 1). Clients and retry
     * libraries honour this instead of hammering a blocked key — the same "back off
     * until reset" contract the comparable gateways advertise.
     */
    private static long retryAfterSeconds(Instant until) {
        return Math.max(1, Duration.between(Instant.now(), until).getSeconds());
    }
}
