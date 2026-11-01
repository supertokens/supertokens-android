package com.supertokens.session;

import java.util.ArrayList;
import java.util.List;

final class RecordingEventHandler implements EventHandler {
    static final class Delivery {
        final EventType type;
        final Thread thread;
        final int order;
        final boolean beforeReturn;

        Delivery(EventType type, int order, boolean beforeReturn) {
            this.type = type;
            this.thread = Thread.currentThread();
            this.order = order;
            this.beforeReturn = beforeReturn;
        }
    }

    private final List<Delivery> deliveries = new ArrayList<>();
    private boolean returned;

    synchronized void beginRequest() { returned = false; }
    synchronized void requestReturned() { returned = true; }
    synchronized List<Delivery> deliveries() { return new ArrayList<>(deliveries); }

    @Override public synchronized void handleEvent(EventType type) {
        deliveries.add(new Delivery(type, deliveries.size(), !returned));
    }
}
