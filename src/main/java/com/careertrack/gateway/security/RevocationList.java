package com.careertrack.gateway.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory copy of Auth's active token revocations (ADR 0001, stage C2),
 * refreshed by {@link RevocationPoller}. Lookups are lock-free: each poll
 * swaps in a new immutable snapshot.
 *
 * <p>A token is revoked when its session was logged out, or when it was
 * issued before its user's "logout everywhere" cut-off.
 */
public class RevocationList {

    /** Wire format of GET /internal/revocations (times in epoch seconds). */
    public record Entry(String type, String sub, String sid, Long notBefore, Long expiresAt) {
    }

    public record Payload(Long serverTime, List<Entry> revocations) {
    }

    private record Snapshot(Set<String> sessions, Map<String, Long> userNotBefore, Instant loadedAt) {
        static final Snapshot EMPTY = new Snapshot(Set.of(), Map.of(), null);
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.EMPTY);

    public boolean isRevoked(String subject, String sessionId, Instant issuedAt) {
        Snapshot snapshot = current.get();

        if (sessionId != null && snapshot.sessions().contains(sessionId)) {
            return true;
        }

        Long notBefore = snapshot.userNotBefore().get(subject);
        // A token without iat cannot prove it is newer than the cut-off.
        return notBefore != null
                && (issuedAt == null || issuedAt.getEpochSecond() < notBefore);
    }

    public void replace(Payload payload, Instant now) {
        Set<String> sessions = new HashSet<>();
        Map<String, Long> userNotBefore = new HashMap<>();

        if (payload != null && payload.revocations() != null) {
            for (Entry e : payload.revocations()) {
                if (e == null || e.sub() == null) {
                    continue;
                }
                if ("SESSION".equals(e.type()) && e.sid() != null) {
                    sessions.add(e.sid());
                } else if ("USER".equals(e.type()) && e.notBefore() != null) {
                    // Several logout-all entries: the latest cut-off wins.
                    userNotBefore.merge(e.sub(), e.notBefore(), Math::max);
                }
            }
        }

        current.set(new Snapshot(Set.copyOf(sessions), Map.copyOf(userNotBefore), now));
    }

    /** When the last successful poll happened; null if never. */
    public Instant loadedAt() {
        return current.get().loadedAt();
    }

    public int size() {
        Snapshot s = current.get();
        return s.sessions().size() + s.userNotBefore().size();
    }
}
