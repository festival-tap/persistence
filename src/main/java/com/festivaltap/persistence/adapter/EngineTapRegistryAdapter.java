package com.festivaltap.persistence.adapter;

import com.festivaltap.core.exception.InvalidTapCommandException;
import com.festivaltap.core.model.Tap;
import com.festivaltap.core.ports.out.TapRegistryPort;
import com.sugarcoatlabs.persistence.exception.DuplicateEntityException;
import com.sugarcoatlabs.persistence.state.StateStoreEngine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EngineTapRegistryAdapter implements TapRegistryPort {

    private final StateStoreEngine<String, Tap> stateStore;

    public EngineTapRegistryAdapter(StateStoreEngine<String, Tap> stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
    }

    @Override
    public void save(Tap tap) {
        if (tap == null) {
            throw new IllegalArgumentException("Tap must not be null");
        }
        try {
            stateStore.putIfAbsent(tap.id(), tap);
        } catch (DuplicateEntityException ex) {
            throw new InvalidTapCommandException("Tap already registered with id: " + tap.id(), ex);
        }
    }

    @Override
    public Optional<Tap> findById(String id) {
        validateId(id);
        return stateStore.findById(id);
    }

    @Override
    public List<Tap> findAll() {
        return stateStore.findAll();
    }

    @Override
    public boolean exists(String id) {
        validateId(id);
        return stateStore.exists(id);
    }

    private void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Tap id must not be null or blank");
        }
    }
}
