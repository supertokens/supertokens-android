package com.example.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpgradeFixtureTest {
    @Test public void assertionFailureCleansKnownOwner() throws Exception {
        List<String> cleaned = new ArrayList<>();
        AssertionError original = new AssertionError("scenario failed");
        try (UpgradeFixture fixture = UpgradeFixture.open((path, body) -> {
            if (path.equals("/test/email-change-owner")) return new JSONObject().put("fixtureId", "owned").put("userId", "user");
            cleaned.add(body.getString("fixtureId"));
            return new JSONObject().put("cleaned", true);
        })) { throw original; }
        catch (AssertionError failure) { assertSame(original, failure); }
        assertEquals(java.util.Collections.singletonList("owned"), cleaned);
    }

    @Test public void malformedKnownOwnerResponseStillCleans() throws Exception {
        List<String> cleaned = new ArrayList<>();
        try {
            UpgradeFixture.open((path, body) -> {
                if (path.equals("/test/email-change-owner")) return new JSONObject().put("fixtureId", "owned");
                cleaned.add(body.getString("fixtureId"));
                return new JSONObject().put("cleaned", true);
            });
            fail("Missing user must fail");
        } catch (org.json.JSONException expected) { }
        assertEquals(java.util.Collections.singletonList("owned"), cleaned);
    }

    @Test public void lostCreationResponseDoesNotInventCleanupOwner() throws Exception {
        List<String> paths = new ArrayList<>();
        IOException original = new IOException("response lost");
        try {
            UpgradeFixture.open((path, body) -> { paths.add(path); throw original; });
            fail("Lost response must fail");
        } catch (IOException failure) { assertSame(original, failure); }
        assertEquals(java.util.Collections.singletonList("/test/email-change-owner"), paths);
    }

    @Test public void unacknowledgedCleanupFailsAndPreservesScenarioFailure() throws Exception {
        AssertionError original = new AssertionError("scenario failed");
        try (UpgradeFixture fixture = UpgradeFixture.open((path, body) -> path.equals("/test/email-change-owner")
                ? new JSONObject().put("fixtureId", "owned").put("userId", "user")
                : new JSONObject().put("cleaned", false))) { throw original; }
        catch (AssertionError failure) {
            assertSame(original, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0].getMessage().contains("not acknowledged"));
        }
    }
}
