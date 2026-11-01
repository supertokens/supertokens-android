package com.supertokens.session;

import android.content.Context;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RepeatedInitTest extends SessionTestFixture {
    @Test public void repeatedBuildsKeepOwnerContextConfigurationAndCallbacks() throws Exception {
        SessionSnapshot before = install("A", "active");
        SessionState.Configuration active = SessionState.configuration();
        Utils.NormalisedInputType publicConfig = SuperTokens.config;
        int commits = preferenceStore.commits;
        Context other = mock(Context.class);
        for (int i = 0; i < 3; i++) {
            new SuperTokens.Builder(other, server.url("/").toString())
                    .apiBasePath("/other").sessionExpiredStatusCode(403)
                    .maxRetryAttemptsForSessionRefresh(3).tokenTransferMethod("cookie")
                    .customHeaderProvider(new CustomHeaderProvider.DefaultCustomHeaderMapper() {})
                    .eventHandler(event -> { throw new AssertionError("Ignored callback invoked"); }).build();
        }
        assertSame(before, SessionState.snapshot());
        assertSame(active, SessionState.configuration());
        assertSame(publicConfig, SuperTokens.config);
        assertSame(context, SuperTokens.contextWeakReference.get());
        assertEquals(commits, preferenceStore.commits);
        verifyZeroInteractions(other);
        assertTrue(SuperTokens.clearSession(context, before.getOwner()).isSuccess());
    }

    @Test public void malformedSecondConfigurationIsIgnoredBeforeValidation() throws Exception {
        SessionSnapshot before = install("A", "active");
        for (String domain : new String[]{null, "http://[", ""}) {
            new SuperTokens.Builder(null, domain).apiBasePath("bad")
                    .sessionExpiredStatusCode(-1).maxRetryAttemptsForSessionRefresh(-1)
                    .sessionTokenBackendDomain("http://[").tokenTransferMethod("invalid").build();
        }
        assertSame(before, SessionState.snapshot());
    }

    @Test public void malformedFirstBuildStillFailsAndCanBeRetried() throws Exception {
        SuperTokens.resetForTests();
        try {
            new SuperTokens.Builder(context, "http://[").build();
            fail("Invalid first configuration must fail");
        } catch (java.net.MalformedURLException | IllegalArgumentException expected) { }
        assertFalse(SuperTokens.isInitCalled);
        initializeSession();
        assertTrue(SuperTokens.isInitCalled);
    }

    @Test public void testappFlagResetReadsNewContextAndConfiguration() throws Exception {
        SessionSnapshot before = install("A", "active");
        Context fresh = mock(Context.class);
        when(fresh.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences());
        Field initialized = SuperTokens.class.getDeclaredField("isInitCalled");
        initialized.setAccessible(true);
        initialized.set(null, false);
        new SuperTokens.Builder(fresh, server.url("/").toString()).apiBasePath("/new")
                .tokenTransferMethod("cookie").build();
        assertFalse(before.isCurrent());
        assertSame(fresh, SuperTokens.contextWeakReference.get());
        assertEquals("/new", SessionState.configuration().values.apiBasePath);
        assertEquals("cookie", SessionState.configuration().values.tokenTransferMethod);
        assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
    }

    @Test public void concurrentFirstBuildWinsBeforeSecondValidation() throws Exception {
        SuperTokens.resetForTests();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        when(context.getApplicationContext()).thenAnswer(invocation -> {
            firstEntered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Initialization blocked");
            return null;
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> { initializeSession(); return null; });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() -> {
                secondEntered.countDown();
                new SuperTokens.Builder(null, "http://[").build();
                return null;
            });
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertSame(context, SuperTokens.contextWeakReference.get());
            assertEquals("header", SessionState.configuration().values.tokenTransferMethod);
            verify(context, times(2)).getApplicationContext();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
}
