package com.supertokens.upgrade;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecoveryControls {
    private static int passed;
    private static final Map<String, Object> EMPTY = Collections.emptyMap();

    private static final class State implements OwnedRecoveryGuard.Captured {
        String currentOwner = "A";
        String status = "AVAILABLE";
        String user = "user-A";
        String handle = "handle-A";
        boolean replaceBeforeClear;
        int clears;
        @Override public String status() { return status; }
        @Override public String userId() { return user; }
        @Override public String handle() { return handle; }
        @Override public boolean clearCapturedOwner() {
            clears++;
            if (replaceBeforeClear) currentOwner = "B";
            if (!"A".equals(currentOwner)) return false;
            currentOwner = null;
            return true;
        }
    }

    private static void recover(State state, Map<String, ?> cookies) {
        OwnedRecoveryGuard.recover(state, "user-A", "handle-A", cookies);
    }

    private static void rejected(State state, Map<String, ?> cookies) {
        boolean failed = false;
        try { recover(state, cookies); } catch (AssertionError expected) { failed = true; }
        check(failed, "unsafe recovery accepted");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        State exact = new State();
        recover(exact, EMPTY);
        check(exact.currentOwner == null && exact.clears == 1, "exact owner not cleared");
        passed++;

        State race = new State();
        race.replaceBeforeClear = true;
        rejected(race, EMPTY);
        check("B".equals(race.currentOwner) && race.clears == 1, "replacement removed");
        passed++;

        for (String status : Arrays.asList("ABSENT", "UNAVAILABLE", "RECOVERY_REQUIRED")) {
            State ambiguous = new State();
            ambiguous.status = status;
            // Even matching cached labels cannot authorize hidden residual credentials.
            rejected(ambiguous, EMPTY);
            check(ambiguous.clears == 0, "ambiguous owner cleared");
            passed++;
        }

        State other = new State();
        other.user = "user-B";
        rejected(other, EMPTY);
        check(other.clears == 0, "other user cleared");
        passed++;
        State otherHandle = new State();
        otherHandle.handle = "handle-B";
        rejected(otherHandle, EMPTY);
        check(otherHandle.clears == 0, "other handle cleared");
        passed++;

        for (String value : Arrays.asList("owned-A-value-domain-path", "replacement-B-same-host-name")) {
            Map<String, Object> cookies = new HashMap<String, Object>();
            cookies.put("http://same.host/auth|sRefreshToken", value);
            State cookieState = new State();
            rejected(cookieState, cookies);
            check(value.equals(cookies.get("http://same.host/auth|sRefreshToken"))
                    && cookieState.clears == 0, "cookie residue changed");
            passed++;
        }

        final Exception primary = new Exception("original stage failure");
        final List<String> attempted = new java.util.ArrayList<String>();
        final List<String> acknowledged = new java.util.ArrayList<String>();
        boolean failed = false;
        try {
            CleanupRecovery.run(primary, new CleanupRecovery.Actions() {
                @Override public void signOut() { throw new AssertionError("local identity refusal"); }
                @Override public void assertAbsent() { throw new AssertionError("residue retained"); }
                @Override public List<String> owners() { return Arrays.asList("A", "B", "C"); }
                @Override public void cleanupOwner(String owner) throws Exception {
                    attempted.add(owner);
                    if (owner.equals("B")) throw new Exception("backend refusal");
                }
                @Override public void acknowledge(String owner) { acknowledged.add(owner); }
                @Override public void assertNoPendingCreation() { }
                @Override public void complete() { throw new AssertionError("must not mark complete"); }
            });
        } catch (CleanupRecovery.Failure expected) {
            failed = true;
            check(expected.getCause() == primary, "original failure lost");
            check(expected.cleanupFailures.size() == 3, "cleanup failures lost");
        }
        check(failed && attempted.equals(Arrays.asList("A", "B", "C"))
                && acknowledged.equals(Arrays.asList("A", "C")), "backend cleanup not independent");
        passed++;
        System.out.println("PASS " + passed + " recovery controls (host policy/model, not device execution)");
    }
}
