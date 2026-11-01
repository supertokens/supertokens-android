package com.supertokens.upgrade;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** Independent cleanup steps retain failures without requiring API19 addSuppressed. */
final class CleanupRecovery {
    interface Actions {
        void signOut() throws Exception;
        void assertAbsent() throws Exception;
        List<String> owners() throws Exception;
        void cleanupOwner(String owner) throws Exception;
        void acknowledge(String owner) throws Exception;
        void assertNoPendingCreation() throws Exception;
        void complete() throws Exception;
    }

    static void run(Throwable primary, Actions actions) throws Exception {
        List<Throwable> failures = new ArrayList<Throwable>();
        try { actions.signOut(); } catch (Throwable failure) { failures.add(failure); }
        try { actions.assertAbsent(); } catch (Throwable failure) { failures.add(failure); }
        List<String> owners = new ArrayList<String>();
        try { owners = new ArrayList<String>(actions.owners()); }
        catch (Throwable failure) { failures.add(failure); }
        for (String owner : owners) {
            try {
                actions.cleanupOwner(owner);
                actions.acknowledge(owner);
            } catch (Throwable failure) { failures.add(failure); }
        }
        try { actions.assertNoPendingCreation(); } catch (Throwable failure) { failures.add(failure); }
        if (failures.isEmpty()) {
            try { actions.complete(); } catch (Throwable failure) { failures.add(failure); }
        }
        if (!failures.isEmpty()) throw new Failure(primary, failures);
        if (primary instanceof Error) throw (Error) primary;
        if (primary != null) throw (Exception) primary;
    }

    static final class Failure extends Exception {
        final List<Throwable> cleanupFailures;

        Failure(Throwable primary, List<Throwable> failures) {
            super("Scoped cleanup incomplete (" + failures.size() + " failures); original cause retained",
                    primary == null ? failures.get(0) : primary);
            cleanupFailures = new ArrayList<Throwable>(failures);
        }

        @Override public void printStackTrace(PrintStream stream) {
            super.printStackTrace(stream);
            for (Throwable failure : cleanupFailures) {
                stream.println("Additional cleanup failure:");
                failure.printStackTrace(stream);
            }
        }

        @Override public void printStackTrace(PrintWriter writer) {
            super.printStackTrace(writer);
            for (Throwable failure : cleanupFailures) {
                writer.println("Additional cleanup failure:");
                failure.printStackTrace(writer);
            }
        }
    }
}
