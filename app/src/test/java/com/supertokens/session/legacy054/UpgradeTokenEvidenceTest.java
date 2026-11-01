package com.supertokens.session.legacy054;

import com.supertokens.session.SessionTestFixture;
import com.supertokens.testing.PublishedUpgradeScenario;
import com.supertokens.testing.PublishedUpgradeScenario.Flow;
import com.supertokens.testing.PublishedUpgradeScenario.Mode;
import com.supertokens.testing.PublishedUpgradeScenario.TokenEvidence;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class UpgradeTokenEvidenceTest extends SessionTestFixture {
    @Parameterized.Parameters(name = "{0}-{1}-version-transitions") public static Collection<Object[]> rows() {
        Collection<Object[]> rows = new ArrayList<>();
        for (Mode mode : Mode.values()) for (Flow flow : Flow.values()) rows.add(new Object[]{mode, flow});
        return rows;
    }
    private final Mode mode;
    private final Flow flow;
    public UpgradeTokenEvidenceTest(Mode mode, Flow flow) { this.mode = mode; this.flow = flow; }
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void independentlyObservedPhasesRejectIncorrectConfiguredVersions() throws Exception {
        isolateUpgradeCookies();
        server.setDispatcher(new Legacy054UpgradeTest.Backend(mode, 2, 5, 4));
        TokenEvidence evidence = PublishedUpgradeScenario.run(context, mode, flow, new PublishedUpgradeScenario.Routes(
                server.url("/").toString().replaceAll("/$", ""), "/login", "/protected", "/update-jwt", "{}", "1.16"),
                this::flushAndRestartPreferences);
        String handoff = flow == Flow.SIGN_OUT ? TokenEvidence.ABSENT
                : flow == Flow.REFRESH ? "5" : flow == Flow.PAYLOAD_UPDATE ? "4" : "2";
        evidence.assertVersions("2", handoff, "5");
        reject(() -> evidence.assertVersions("3", null, null), "publishedLogin");
        reject(() -> evidence.assertVersions(null, "2".equals(handoff) ? "5" : "2", null), "publishedHandoff");
        reject(() -> evidence.assertVersions(null, null, "2"), "candidateRefresh");
        assertEquals(2, evidence.toJson().getInt("schemaVersion"));
        if (flow == Flow.SIGN_OUT) {
            assertEquals("ABSENT", evidence.toJson().getJSONObject("publishedHandoff").getString("status"));
            assertTrue(evidence.toJson().getJSONObject("publishedHandoff").isNull("tokenVersion"));
        } else {
            assertEquals(handoff, evidence.toJson().getJSONObject("publishedHandoff").getString("tokenVersion"));
        }
    }

    private static void reject(Runnable assertion, String phase) {
        try { assertion.run(); }
        catch (AssertionError expected) { assertTrue(expected.getMessage().contains(phase)); return; }
        fail("Incorrect " + phase + " expectation passed");
    }
}
