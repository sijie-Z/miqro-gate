package com.miqroera.miqrokey.testing.compatibility;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, fixed-capacity in-memory store of {@link RequestObservation}
 * records.
 *
 * <h3>Guarantees</h3>
 * <ul>
 * <li>Capacity is positive and fixed at construction time.</li>
 * <li>When the store is full, the <strong>oldest</strong> entry is evicted
 * deterministically before the new entry is inserted.</li>
 * <li>{@link #snapshot()} returns an immutable, point-in-time copy that is safe
 * to iterate under concurrent writes.</li>
 * <li>{@link #clear()} discards all current observations.</li>
 * <li>No internal collection ever grows beyond {@link #capacity()}.</li>
 * </ul>
 */
public final class ObservationStore {

    private final int capacity;
    private final Deque<RequestObservation> observations;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Creates a new store with the given fixed capacity.
     *
     * @param capacity
     *            positive maximum number of observations to retain
     * @throws IllegalArgumentException
     *             if {@code capacity} is zero or negative
     */
    public ObservationStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was: " + capacity);
        }
        this.capacity = capacity;
        this.observations = new ArrayDeque<>(capacity);
    }

    /**
     * Records a new observation. If the store is already at capacity the oldest
     * entry is evicted first.
     *
     * @param observation
     *            the observation to record; must not be null
     */
    public void record(RequestObservation observation) {
        lock.lock();
        try {
            if (observations.size() >= capacity) {
                observations.pollFirst();
            }
            observations.addLast(observation);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an immutable point-in-time snapshot of all currently stored
     * observations in insertion order (oldest first).
     *
     * @return immutable list; never null, may be empty
     */
    public List<RequestObservation> snapshot() {
        lock.lock();
        try {
            return List.copyOf(observations);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Discards all observations from this store.
     */
    public void clear() {
        lock.lock();
        try {
            observations.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of observations currently in the store.
     */
    public int size() {
        lock.lock();
        try {
            return observations.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the fixed maximum capacity of this store.
     */
    public int capacity() {
        return capacity;
    }
}
