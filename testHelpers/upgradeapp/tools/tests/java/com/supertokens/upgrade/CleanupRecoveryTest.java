package com.supertokens.upgrade;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CleanupRecoveryTest {
    private static final class Fake implements CleanupRecovery.Actions {
        final List<String> pending = new ArrayList<String>(Arrays.asList("A", "B"));
        final List<String> attempted = new ArrayList<String>();
        final List<String> acknowledged = new ArrayList<String>();
        boolean signOutFails, absenceFails, firstOwnerFails, unknownCreation, commitFails, completed;
        @Override public void signOut() { if (signOutFails) throw new AssertionError("signout failed"); }
        @Override public void assertAbsent() { if (absenceFails) throw new AssertionError("absence failed"); }
        @Override public List<String> owners() { return pending; }
        @Override public void cleanupOwner(String id) {
            attempted.add(id);
            if (firstOwnerFails && id.equals("A")) throw new AssertionError("owner A failed");
        }
        @Override public void acknowledge(String id) {
            if (commitFails && id.equals("A")) throw new AssertionError("receipt failed");
            acknowledged.add(id);
            pending.remove(id);
        }
        @Override public void assertNoPendingCreation() {
            if (unknownCreation) throw new AssertionError("pending request unresolved");
        }
        @Override public void complete() { completed = true; }
    }

    private CleanupRecovery.Failure failRecovery(Throwable primary, Fake fake) throws Exception {
        try {
            CleanupRecovery.run(primary, fake);
            fail("Recovery must fail");
            return null;
        } catch (CleanupRecovery.Failure failure) {
            assertFalse(fake.completed);
            assertEquals(Arrays.asList("A", "B"), fake.attempted);
            return failure;
        }
    }

    @Test public void signOutFailureStillAcknowledgesEveryOwner() throws Exception {
        Fake fake = new Fake();
        fake.signOutFails = true;
        failRecovery(null, fake);
        assertEquals(Arrays.asList("A", "B"), fake.acknowledged);
        assertTrue(fake.pending.isEmpty());
    }

    @Test public void absenceFailureStillAcknowledgesEveryOwner() throws Exception {
        Fake fake = new Fake();
        fake.absenceFails = true;
        failRecovery(null, fake);
        assertEquals(Arrays.asList("A", "B"), fake.acknowledged);
    }

    @Test public void firstOwnerFailureRetainsItAndStillAttemptsSecond() throws Exception {
        Fake fake = new Fake();
        fake.firstOwnerFails = true;
        failRecovery(null, fake);
        assertEquals(Arrays.asList("A"), fake.pending);
        assertEquals(Arrays.asList("B"), fake.acknowledged);
        fake.firstOwnerFails = false;
        fake.attempted.clear();
        CleanupRecovery.run(null, fake);
        assertEquals(Arrays.asList("A"), fake.attempted);
        assertTrue(fake.completed);
    }

    @Test public void pendingCreationDoesNotBlockKnownOwnersOrBecomeComplete() throws Exception {
        Fake fake = new Fake();
        fake.unknownCreation = true;
        failRecovery(null, fake);
        assertTrue(fake.pending.isEmpty());
        assertTrue(fake.unknownCreation);
    }

    @Test public void originalFailureAndEveryCleanupFailureSurvive() throws Exception {
        Fake fake = new Fake();
        fake.signOutFails = fake.absenceFails = fake.firstOwnerFails = fake.unknownCreation = true;
        AssertionError primary = new AssertionError("original scenario failed");
        CleanupRecovery.Failure failure = failRecovery(primary, fake);
        assertSame(primary, failure.getCause());
        assertEquals(4, failure.cleanupFailures.size());
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        for (String message : Arrays.asList("original scenario failed", "signout failed", "absence failed",
                "owner A failed", "pending request unresolved")) assertTrue(text.toString().contains(message));
        assertEquals(Arrays.asList("A"), fake.pending);
    }

    @Test public void successfulRecoveryRethrowsIdenticalPrimary() throws Exception {
        Fake fake = new Fake();
        Exception primary = new Exception("scenario failed");
        try {
            CleanupRecovery.run(primary, fake);
            fail("Scenario must remain failed");
        } catch (Exception failure) { assertSame(primary, failure); }
        assertTrue(fake.completed);
        assertTrue(fake.pending.isEmpty());
    }

    @Test public void acknowledgmentFailureRetainsOwnerAndContinues() throws Exception {
        Fake fake = new Fake();
        fake.commitFails = true;
        failRecovery(null, fake);
        assertEquals(Arrays.asList("A"), fake.pending);
        assertEquals(Arrays.asList("B"), fake.acknowledged);
    }
}
