package com.supertokens.session;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class MalformedRefreshFlightTest extends SessionTestFixture {
    private SessionResponse malformed() {
        return new SessionResponse(401, "", Collections.singletonMap("front-token", Arrays.asList("remove", "remove")));
    }

    @Test public void duplicateRemovalCompletesSharedFlightAndAllowsRetry() throws Exception {
        for (boolean leaderLegacy : new boolean[]{false, true}) {
            for (boolean followerLegacy : new boolean[]{false, true}) {
                SessionSnapshot before = install("shared", leaderLegacy + "-" + followerLegacy);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                CountDownLatch joined = new CountDownLatch(1);
                RefreshCoordinator.Transport transport = (ctx, config, captured) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                    return malformed();
                };
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<SessionResolution> leader = workers.submit(() -> RefreshCoordinator.resolve(context,
                            before.getOwner(), true, null, transport, leaderLegacy));
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    Future<SessionResolution> follower = workers.submit(() -> {
                        RefreshCoordinator.Acquisition acquired = SessionState.acquireRefresh(before.getOwner(), true,
                                null, transport, before.getRefreshTokenRevision(), followerLegacy);
                        assertFalse(acquired.leader);
                        assertNotNull(acquired.flight);
                        joined.countDown();
                        return acquired.flight.await();
                    });
                    assertTrue(joined.await(5, TimeUnit.SECONDS));
                    release.countDown();
                    SessionResolution first = leader.get(5, TimeUnit.SECONDS);
                    SessionResolution second = follower.get(5, TimeUnit.SECONDS);
                    assertEquals(SessionResolution.Status.REFRESH_FAILED, first.getStatus());
                    assertEquals("Malformed refresh response", first.getError().getMessage());
                    assertTrue(first.getError().getCause() instanceof IllegalArgumentException);
                    assertEquals(SessionResolution.Status.REFRESH_FAILED, second.getStatus());
                    assertSame(first.getError(), second.getError());
                    assertSame(before, SuperTokens.getSessionSnapshot(context));

                    SessionTokens replacement = tokens("shared", "retry");
                    Map<String, List<String>> headers = new HashMap<>();
                    headers.put("front-token", Collections.singletonList(replacement.getFrontToken()));
                    headers.put("st-access-token", Collections.singletonList(replacement.getAccessToken()));
                    headers.put("st-refresh-token", Collections.singletonList(replacement.getRefreshToken()));
                    SessionResolution retry = RefreshCoordinator.resolve(context, before.getOwner(), true, null,
                            (ctx, config, captured) -> new SessionResponse(200, "", headers), false);
                    assertTrue(retry.isSuccess());
                    assertSame(before.getOwner(), retry.getSnapshot().getOwner());
                } finally {
                    release.countDown();
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test public void lateMalformedResponseCannotDisturbReplacement() throws Exception {
        SessionSnapshot before = install("old", "before");
        RefreshCoordinator.Acquisition acquired = SessionState.acquireRefresh(before.getOwner(), true, null,
                (ctx, config, captured) -> malformed(), before.getRefreshTokenRevision());
        assertTrue(acquired.leader);
        assertTrue(SessionState.startRefresh(acquired.flight));
        SessionSnapshot replacement = install("new", "after");
        SessionState.completeRefresh(context, acquired.flight, malformed(), null);
        assertEquals(SessionResolution.Status.STALE_OWNER, acquired.flight.await().getStatus());
        assertSame(replacement, SuperTokens.getSessionSnapshot(context));
    }
}
