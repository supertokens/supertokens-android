package com.supertokens.session;

import java.util.IdentityHashMap;

/** Immutable notifications owned by the operation that committed the transition. */
final class LegacyEventBatch {
    static final LegacyEventBatch EMPTY = new LegacyEventBatch(null);
    private static final ThreadLocal<FailureScope> failureScope = new ThreadLocal<>();
    private final EventHandler handler;
    private final EventHandler.EventType[] events;

    LegacyEventBatch(EventHandler handler, EventHandler.EventType... events) {
        this.handler = handler;
        this.events = events.clone();
    }

    void deliver() {
        try {
            for (EventHandler.EventType event : events) handler.handleEvent(event);
        } catch (RuntimeException failure) {
            for (FailureScope scope = failureScope.get(); scope != null; scope = scope.parent) {
                scope.failures.put(failure, Boolean.TRUE);
            }
            throw failure;
        }
    }

    // Tracks exception identity only while a refresh transport may reenter event-producing APIs.
    // Events themselves never live in thread-local storage or wait for another operation to deliver them.
    static final class FailureScope {
        private final FailureScope parent = failureScope.get();
        private final IdentityHashMap<RuntimeException, Boolean> failures = new IdentityHashMap<>();

        FailureScope() { failureScope.set(this); }

        boolean contains(RuntimeException failure) { return failures.containsKey(failure); }

        void close() {
            if (parent == null) failureScope.remove();
            else failureScope.set(parent);
        }
    }

}
