package com.syncsphere.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers the most recent N event IDs so the same event is never delivered to clients twice.
 *
 * <p>A {@link LinkedHashMap} keeps insertion order; overriding {@code removeEldestEntry} turns it into a
 * fixed-size structure that forgets the oldest ID once the capacity is exceeded. Memory use is therefore
 * bounded, at the cost that a duplicate arriving after 10,000 newer events would not be detected
 * (clients still ignore it because its revision is old).
 *
 * <p>Methods are {@code synchronized} because Redis listener threads and STOMP inbound threads
 * (the local fallback path) can call it concurrently.
 */
public class RecentEventIds {

    private final Map<UUID, Boolean> seen;

    public RecentEventIds(int capacity) {
        this.seen = new LinkedHashMap<>(capacity, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * @return {@code true} the first time an ID is seen, {@code false} for every repeat
     */
    public synchronized boolean markSeen(UUID eventId) {
        return seen.put(eventId, Boolean.TRUE) == null;
    }
}
