package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.Utils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class UrlScopeParityTest {
    @Test public void publishedHostBackendRulesMatchAcrossProtocolsPortsAndPaths() throws Exception {
        try (Legacy054Harness old = new Legacy054Harness(null)) {
            Class<?> utils = old.sdkClass().getClassLoader().loadClass("com.supertokens.session.Utils");
            Method matches = utils.getMethod("shouldDoInterceptionBasedOnUrl", String.class, String.class, String.class);
            for (String backend : new String[]{null, "example.com", ".example.com", "localhost", "127.0.0.1"}) {
                for (String target : new String[]{"https://example.com/a", "http://example.com:8080/other",
                        "https://sub.example.com:444/a", "http://evil-example.com/", "http://example.com.attacker.test/",
                        "http://localhost:9000/a", "http://127.0.0.1:9000/a"}) {
                    assertEquals(target + " backend=" + backend,
                            matches.invoke(null, target, "https://example.com/base", backend),
                            Utils.shouldDoInterceptionBasedOnUrl(target, "https://example.com/base", backend));
                }
            }
        }
    }

    @Test public void publishedNormalizationIgnoresDomainPathQueryFragmentAndAcceptsNegativeRetry() throws Exception {
        try (Legacy054Harness old = new Legacy054Harness(null)) {
            ClassLoader loader = old.sdkClass().getClassLoader();
            Class<?> type = loader.loadClass("com.supertokens.session.Utils$NormalisedInputType");
            Method normalize = type.getMethod("normaliseInputOrThrowError", String.class, String.class,
                    Integer.class, Integer.class, String.class, String.class,
                    loader.loadClass("com.supertokens.session.CustomHeaderProvider"),
                    loader.loadClass("com.supertokens.session.EventHandler"));
            for (String domain : new String[]{"https://example.com/base", "https://example.com/base?q=1#f",
                    "http://localhost:8000/path/", "example.com/ignored"}) {
                Object expected = normalize.invoke(null, domain, "/custom", null, -2, null, null, null, null);
                Object actual = Utils.NormalisedInputType.normaliseInputOrThrowError(domain, "/custom", null,
                        -2, null, null, null, null);
                assertEquals(read(expected, "apiDomain"), read(actual, "apiDomain"));
                assertEquals(read(expected, "apiBasePath"), read(actual, "apiBasePath"));
                assertEquals(-2, read(expected, "maxRetryAttemptsForSessionRefresh"));
                assertEquals(0, read(actual, "maxRetryAttemptsForSessionRefresh"));
            }
        }
    }

    private static Object read(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
