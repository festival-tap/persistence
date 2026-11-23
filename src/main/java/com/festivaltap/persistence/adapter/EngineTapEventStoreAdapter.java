package com.festivaltap.persistence.adapter;

import com.festivaltap.core.exception.SequenceConflictException;
import com.festivaltap.core.model.TapEvent;
import com.festivaltap.core.ports.out.TapEventFetchingPort;
import com.festivaltap.core.ports.out.TapEventPublishingPort;
import com.sugarcoatlabs.persistence.event.EventStreamEngine;
import com.sugarcoatlabs.persistence.exception.DuplicateEventException;
import com.sugarcoatlabs.persistence.exception.OptimisticConcurrencyException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class EngineTapEventStoreAdapter
        implements TapEventFetchingPort, TapEventPublishingPort {

    private final EventStreamEngine<String, UUID, TapEvent> eventStream;

    public EngineTapEventStoreAdapter(EventStreamEngine<String, UUID, TapEvent> eventStream) {
        this.eventStream = Objects.requireNonNull(eventStream, "eventStream must not be null");
    }

    @Override
    public void append(TapEvent event, long expectedSequenceNumber) {
        if (event == null) {
            throw new IllegalArgumentException("TapEvent must not be null");
        }
        if (expectedSequenceNumber < 1) {
            throw new IllegalArgumentException("expectedSequenceNumber must be >= 1");
        }
        try {
            eventStream.append(
                    event.tapId(),
                    event.eventId(),
                    event.sequenceNumber(),
                    expectedSequenceNumber,
                    event
            );
        } catch (OptimisticConcurrencyException | DuplicateEventException ex) {
            long actualSequence = eventStream.currentSequence(event.tapId());
            throw new SequenceConflictException(event.tapId(), expectedSequenceNumber, actualSequence);
        }
    }

    @Override
    public long getCurrentSequenceNumber(String tapId) {
        validateTapId(tapId);
        return eventStream.currentSequence(tapId);
    }

    @Override
    public List<TapEvent> streamForTap(String tapId) {
        validateTapId(tapId);
        return eventStream.stream(tapId);
    }

    private void validateTapId(String tapId) {
        if (tapId == null || tapId.isBlank()) {
            throw new IllegalArgumentException("tapId must not be null or blank");
        }
    }
}
