package com.syncsphere.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecentEventIdsTest {

    @Test
    void firstSightingIsNewAndRepeatsAreDuplicates() {
        RecentEventIds ids = new RecentEventIds(10);
        UUID event = UUID.randomUUID();

        assertThat(ids.markSeen(event)).isTrue();
        assertThat(ids.markSeen(event)).isFalse();
        assertThat(ids.markSeen(event)).isFalse();
    }

    @Test
    void oldestIdIsForgottenOnceCapacityIsExceeded() {
        RecentEventIds ids = new RecentEventIds(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        ids.markSeen(first);
        ids.markSeen(second);
        ids.markSeen(third); // evicts "first"

        assertThat(ids.markSeen(second)).as("still remembered").isFalse();
        assertThat(ids.markSeen(first)).as("evicted, so treated as new").isTrue();
    }
}
