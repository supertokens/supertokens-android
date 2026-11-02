package com.supertokens.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/** Synthetic header-mode model. Parent grace is an explicit test policy, NOT verified Core parity. */
final class FakeBackend extends Dispatcher {
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final AtomicInteger activeRefreshes = new AtomicInteger();
    final AtomicInteger maximumRefreshOverlap = new AtomicInteger();
    final AtomicInteger refreshAttempts = new AtomicInteger();
    final AtomicInteger reuseRejections = new AtomicInteger();
    private final SessionTokens initial;
    private SessionTokens current;
    private String parentRefresh;
    private int parentUses;
    private int graceUsesPerRotation;
    private int generation;
    private boolean revoked;
    private volatile CountDownLatch refreshEntered;
    private volatile CountDownLatch refreshRelease;

    FakeBackend(SessionTokens initial) { this.initial = initial; current = initial; }
    synchronized SessionTokens currentTokens() { return current; }
    synchronized List<RecordedRequest> requests() { return new ArrayList<>(requests); }
    synchronized void parentGraceUses(int uses) {
        if (uses < 0) throw new IllegalArgumentException("Negative grace uses");
        graceUsesPerRotation = uses;
    }

    void gateRefreshes(CountDownLatch entered, CountDownLatch release) {
        refreshRelease = release;
        refreshEntered = entered;
    }

    @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        synchronized (this) { requests.add(request); }
        String path = request.getPath().split("\\?", 2)[0];
        if (path.equals("/auth/session/refresh")) {
            refreshAttempts.incrementAndGet();
            int active = activeRefreshes.incrementAndGet();
            maximumRefreshOverlap.accumulateAndGet(active, Math::max);
            try {
                CountDownLatch entered = refreshEntered;
                CountDownLatch release = refreshRelease;
                if (entered != null) entered.countDown();
                if (release != null && !release.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(503);
                return refresh(request.getHeader("Authorization"));
            } finally { activeRefreshes.decrementAndGet(); }
        }
        synchronized (this) {
            if (path.equals("/login")) return SessionTestFixture.rotation(current).setBody("{\"status\":\"OK\"}");
            if (path.equals("/auth/signout")) {
                if (!revoked && !("Bearer " + current.getAccessToken()).equals(request.getHeader("Authorization"))) {
                    return new MockResponse().setResponseCode(401);
                }
                revoked = true;
                return new MockResponse().setBody("{\"status\":\"OK\"}").addHeader("front-token", "remove");
            }
            return new MockResponse().setResponseCode(!revoked && ("Bearer " + current.getAccessToken()).equals(request.getHeader("Authorization")) ? 200 : 401);
        }
    }

    private synchronized MockResponse refresh(String authorization) {
        if (authorization == null || authorization.isEmpty()) return new MockResponse().setResponseCode(401);
        if (revoked) return new MockResponse().setResponseCode(401).addHeader("front-token", "remove");
        if (("Bearer " + current.getRefreshToken()).equals(authorization)) {
            parentRefresh = current.getRefreshToken();
            parentUses = graceUsesPerRotation;
            String suffix = "-rotation-" + ++generation;
            // Only the synthetic signature changes; claims, token version and front payload stay intact.
            current = new SessionTokens(initial.getAccessToken() + suffix,
                    initial.getRefreshToken() + suffix, initial.getFrontToken(), initial.getAntiCSRF());
            return SessionTestFixture.rotation(current);
        }
        if (parentRefresh != null && ("Bearer " + parentRefresh).equals(authorization) && parentUses > 0) {
            parentUses--;
            return SessionTestFixture.rotation(current);
        }
        reuseRejections.incrementAndGet();
        return new MockResponse().setResponseCode(401).addHeader("front-token", "remove");
    }
}
