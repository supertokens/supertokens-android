package com.example.example;

import android.content.Context;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.FrontToken;
import com.supertokens.session.Utils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionGenerationTest {
    private Context context;

    @Before public void setup() throws Exception {
        SuperTokens.resetForTests();
        context = Mockito.mock(Context.class);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(new SPMockBuilder().createSharedPreferences());
        new SuperTokens.Builder(context, "http://localhost").build();
    }

    @Test public void installAndClearAreConditionalAndSnapshotsAreImmutable() {
        SuperTokens.SessionSnapshot empty = SuperTokens.getSessionSnapshot(context);
        assertEquals(0, empty.generation);
        assertTrue(SuperTokens.installSession(context, empty.generation,
                new SuperTokens.SessionTokens("access", "refresh", "front", "csrf")));
        assertFalse(SuperTokens.clearSession(context, empty.generation));
        SuperTokens.SessionSnapshot installed = SuperTokens.getSessionSnapshot(context);
        assertEquals("access", installed.accessToken);
        assertNull(empty.accessToken);
        assertTrue(SuperTokens.clearSession(context, installed.generation));
        assertNull(SuperTokens.getSessionSnapshot(context).refreshToken);
        assertEquals(installed.generation + 1, SuperTokens.getSessionSnapshot(context).generation);
    }

    @Test public void concurrentInstallAndClearHaveExactlyOneWinner() throws Exception {
        final long generation = SuperTokens.getSessionSnapshot(context).generation;
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> install = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return SuperTokens.installSession(context, generation,
                        new SuperTokens.SessionTokens("a", "r", "f", "c"));
            });
            Future<Boolean> clear = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return SuperTokens.clearSession(context, generation);
            });
            start.countDown();
            assertTrue(install.get(5, TimeUnit.SECONDS) ^ clear.get(5, TimeUnit.SECONDS));
            SuperTokens.SessionSnapshot result = SuperTokens.getSessionSnapshot(context);
            assertEquals(generation + 1, result.generation);
            if (result.accessToken == null) {
                assertNull(result.refreshToken);
                assertNull(result.frontToken);
                assertNull(result.antiCsrfToken);
            } else {
                assertEquals("r", result.refreshToken);
                assertEquals("f", result.frontToken);
                assertEquals("c", result.antiCsrfToken);
            }
        } finally { workers.shutdownNow(); }
    }

    @Test(timeout = 5000) public void listenersCanInstallAndClearReentrantly() {
        AtomicInteger notifications = new AtomicInteger();
        SuperTokens.SessionListener listener = snapshot -> {
            if (notifications.incrementAndGet() == 1) {
                assertTrue(SuperTokens.clearSession(context, snapshot.generation));
            }
        };
        SuperTokens.addSessionListener(listener);
        assertTrue(SuperTokens.installSession(context, 0, new SuperTokens.SessionTokens("a", "r", "f", "c")));
        assertEquals(2, notifications.get());
        assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
        SuperTokens.removeSessionListener(listener);
        assertTrue(SuperTokens.clearSession(context, 2));
        assertEquals(2, notifications.get());
    }

    @Test public void listenerRunsWithoutSessionLockHeld() throws Exception {
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            SuperTokens.SessionListener listener = snapshot -> {
                try {
                    assertEquals(snapshot.generation,
                            (long) otherThread.submit(() -> SuperTokens.getSessionSnapshot(context).generation).get(2, TimeUnit.SECONDS));
                } catch (Exception e) { throw new AssertionError("listener holds session lock", e); }
            };
            SuperTokens.addSessionListener(listener);
            assertTrue(SuperTokens.clearSession(context, 0));
            SuperTokens.removeSessionListener(listener);
        } finally { otherThread.shutdownNow(); }
    }

    @Test public void tokensPersistUnderExistingKeysButGenerationRestartsAtZero() throws Exception {
        assertTrue(SuperTokens.installSession(context, 0, new SuperTokens.SessionTokens("a", "r", "f", "c")));
        assertEquals("a", context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                .getString("st-storage-item-st-access-token", null));
        assertEquals("f", context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                .getString("supertokens-android-fronttoken-key", null));
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, "http://localhost").build();
        SuperTokens.SessionSnapshot restored = SuperTokens.getSessionSnapshot(context);
        assertEquals(0, restored.generation);
        assertEquals("a", restored.accessToken);
        assertEquals("r", restored.refreshToken);
        assertEquals("f", restored.frontToken);
        assertEquals("c", restored.antiCsrfToken);
        assertNotNull(restored.lastAccessTokenUpdate);
    }

    @Test public void deprecatedTokenWritersInvalidatePriorGenerationAndClearTogether() {
        long initial = SuperTokens.getSessionSnapshot(context).generation;
        Utils.setToken(Utils.TokenType.ACCESS, "access", context);
        assertFalse(SuperTokens.clearSession(context, initial));
        Utils.setToken(Utils.TokenType.REFRESH, "refresh", context);
        FrontToken.setToken(context, "front");
        SuperTokens.SessionSnapshot installed = SuperTokens.getSessionSnapshot(context);
        assertEquals("access", installed.accessToken);
        assertEquals("refresh", installed.refreshToken);
        assertEquals("front", installed.frontToken);
        FrontToken.removeToken(context);
        SuperTokens.SessionSnapshot cleared = SuperTokens.getSessionSnapshot(context);
        assertEquals(installed.generation + 1, cleared.generation);
        assertNull(cleared.accessToken);
        assertNull(cleared.refreshToken);
        assertNull(cleared.frontToken);
    }
}
