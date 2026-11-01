package com.supertokens.upgrade;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class R8SmokeTest {
    @Test public void runStage() throws Exception {
        R8Smoke.run(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                InstrumentationRegistry.getArguments());
    }
}
