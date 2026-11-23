package com.festivaltap.persistence;

import com.festivaltap.core.exception.InvalidTapCommandException;
import com.festivaltap.core.exception.SequenceConflictException;
import com.festivaltap.core.model.Tap;
import com.festivaltap.core.model.TapEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceIntegrationTest {

    private Persistence.Adapters adapters;

    @BeforeEach
    void setUp() {
        adapters = Persistence.jdk();
    }

    @Test
    void tapRegistryStoresRetrievesAndRejectsDuplicates() {
        Tap tap = new Tap("tap-01", 0.05);

        assertFalse(adapters.tapRegistry().exists("tap-01"));
        assertTrue(adapters.tapRegistry().findById("tap-01").isEmpty());

        adapters.tapRegistry().save(tap);

        assertTrue(adapters.tapRegistry().exists("tap-01"));
        assertEquals(tap, adapters.tapRegistry().findById("tap-01").orElseThrow());
        assertEquals(List.of(tap), adapters.tapRegistry().findAll());

        assertThrows(InvalidTapCommandException.class, () -> adapters.tapRegistry().save(tap));
    }

    @Test
    void eventStoreAppendsChronologicallyAndEnforcesOptimisticConcurrency() {
        String tapId = "tap-01";
        Instant t1 = Instant.parse("2026-11-23T20:00:00Z");
        Instant t2 = Instant.parse("2026-11-23T20:00:10Z");

        assertEquals(0L, adapters.tapEventFetching().getCurrentSequenceNumber(tapId));
        assertTrue(adapters.tapEventFetching().streamForTap(tapId).isEmpty());

        TapEvent openEvent = new TapEvent(1L, UUID.randomUUID(), tapId, "open", t1);
        TapEvent closeEvent = new TapEvent(2L, UUID.randomUUID(), tapId, "close", t2);

        adapters.tapEventPublishing().append(openEvent, 1L);
        adapters.tapEventPublishing().append(closeEvent, 2L);

        assertEquals(2L, adapters.tapEventFetching().getCurrentSequenceNumber(tapId));
        assertEquals(List.of(openEvent, closeEvent), adapters.tapEventFetching().streamForTap(tapId));

        TapEvent staleEvent = new TapEvent(2L, UUID.randomUUID(), tapId, "open", t2.plusSeconds(5));
        assertThrows(SequenceConflictException.class,
                () -> adapters.tapEventPublishing().append(staleEvent, 2L));
    }
}
