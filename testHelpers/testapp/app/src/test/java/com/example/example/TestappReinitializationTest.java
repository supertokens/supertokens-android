package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestappReinitializationTest {
    private Context context() {
        Context context = mock(Context.class);
        SharedPreferences preferences = new SPMockBuilder().createSharedPreferences();
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        return context;
    }

    @After public void cleanup() {
        SuperTokens.resetForTests();
    }

    @Test public void helperResetsStateBeforeNextTestappInitialization() throws Exception {
        SuperTokens.resetForTests();
        Context first = context();
        Context second = context();
        new SuperTokens.Builder(first, "http://localhost:8080")
                .apiBasePath("/first").tokenTransferMethod("header").build();
        SessionSnapshot original = SuperTokens.getSessionSnapshot(first);
        new SuperTokens.Builder(second, "http://localhost:8081")
                .apiBasePath("/second").tokenTransferMethod("cookie").build();
        assertSame(original, SuperTokens.getSessionSnapshot(first));
        verifyZeroInteractions(second);

        com.example.TestUtils.setInitToFalse();
        assertFalse(original.isCurrent());
        new SuperTokens.Builder(second, "http://localhost:8081")
                .apiBasePath("/second").tokenTransferMethod("cookie").build();
        SessionSnapshot reinitialized = SuperTokens.getSessionSnapshot(second);
        assertEquals(SessionSnapshot.Status.ABSENT, reinitialized.getStatus());
        assertTrue(reinitialized.isCurrent());
        assertNotEquals(original.getConfigurationId(), reinitialized.getConfigurationId());
        verify(second, atLeastOnce()).getSharedPreferences(anyString(), anyInt());
    }
}
