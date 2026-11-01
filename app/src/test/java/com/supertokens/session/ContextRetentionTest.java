package com.supertokens.session;

import android.app.Activity;
import android.content.Context;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ContextRetentionTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void activityInitRetainsOnlyDistinctApplicationContext() throws Exception {
        assertRetainedApplication(mock(Activity.class));
    }

    @Test public void contextInitRetainsApplicationAndRepeatedBuildIsCompleteNoOp() throws Exception {
        assertRetainedApplication(mock(Context.class));
    }

    private void assertRetainedApplication(Context supplied) throws Exception {
        Context application = mock(Context.class);
        when(supplied.getApplicationContext()).thenReturn(application);
        when(supplied.getSharedPreferences(anyString(), anyInt())).thenReturn(preferenceStore.preferences);
        when(application.getSharedPreferences(anyString(), anyInt())).thenReturn(preferenceStore.preferences);
        new SuperTokens.Builder(supplied, server.url("/").toString()).build();
        assertSame(application, SuperTokens.contextWeakReference.get());
        assertNotSame(supplied, SuperTokens.contextWeakReference.get());
        SessionSnapshot before = SuperTokens.getSessionSnapshot(application);
        Context ignored = mock(Activity.class);
        new SuperTokens.Builder(ignored, "not-a-url").build();
        assertSame(application, SuperTokens.contextWeakReference.get());
        assertSame(before, SuperTokens.getSessionSnapshot(application));
        verifyZeroInteractions(ignored);
        assertEquals(0, preferenceStore.commits);
    }
}
