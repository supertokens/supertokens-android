package com.supertokens.session;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class AbsentSignOutCallbackTest extends SessionTestFixture {
    @Test public void staleAdmissionIsRejectedWithoutDispatchOrMutation() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .eventHandler(event -> { if (event == EventHandler.EventType.SIGN_OUT) calls.incrementAndGet(); })
                .reconfigure().isSuccess());
        SessionRequestTag absent = SuperTokens.captureRequestTag(context);
        SessionSnapshot replacement = install("B", "replacement");
        assertNull(SessionState.admitAbsentSignOut(absent));
        SessionState.notifyAbsentSignOutIfCurrent(absent);
        try {
            SessionState.notifyAbsentSignOut(absent);
            fail("strict helper accepted stale absence");
        } catch (StaleSessionException expected) { }
        assertEquals(0, calls.get());
        assertEquals(0, server.getRequestCount());
        assertSame(replacement, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void absentCallbackStaleExceptionEscapesByIdentity() throws Exception {
        callbackFailure(false);
    }

    @Test public void absentCallbackReplacementAndStaleExceptionAreBothPreserved() throws Exception {
        callbackFailure(true);
    }

    private void callbackFailure(final boolean replace) throws Exception {
        final StaleSessionException failure = new StaleSessionException();
        final AtomicReference<SessionSnapshot> replacement = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .eventHandler(event -> {
                    if (event != EventHandler.EventType.SIGN_OUT) return;
                    calls.incrementAndGet();
                    if (replace) {
                        try { replacement.set(install("B", "callback")); }
                        catch (Exception error) { throw new AssertionError(error); }
                    }
                    AbsentSignOutCallbackTest.<RuntimeException>throwUnchecked(failure);
                }).reconfigure().isSuccess());
        SessionSnapshot absent = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.ABSENT, absent.getStatus());
        try {
            SuperTokens.signOut(context);
            fail("callback failure was suppressed");
        } catch (StaleSessionException actual) {
            assertSame(failure, actual);
        }
        assertEquals(1, calls.get());
        assertEquals(0, server.getRequestCount());
        assertSame(replace ? replacement.get() : absent, SuperTokens.getSessionSnapshot(context));
        if (replace) assertEquals("B", SuperTokens.getSessionSnapshot(context).getSessionHandle());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
