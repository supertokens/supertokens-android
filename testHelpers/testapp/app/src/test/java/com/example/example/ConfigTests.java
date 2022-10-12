/*
 * Copyright (c) 2022, VRAI Labs and/or its affiliates. All rights reserved.
 *
 * This software is licensed under the Apache License, Version 2.0 (the
 * "License") as published by the Apache Software Foundation.
 *
 * You may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.example.example.android;

import static com.supertokens.session.Utils.NormalisedInputType.normaliseSessionScopeOrThrowErrorForTests;
import static com.supertokens.session.Utils.shouldDoInterceptionBasedOnUrl;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.MalformedURLException;

import com.supertokens.session.NormalisedURLDomain;
import com.supertokens.session.NormalisedURLPath;
import com.supertokens.session.Utils;

public class ConfigTests {

    private String normaliseURLPathOrThrowError(String input) throws MalformedURLException {
        return new NormalisedURLPath(input).getAsStringDangerous();
    }

    private  String normaliseURLDomainOrThrowError(String input) throws MalformedURLException {
        return new NormalisedURLDomain(input).getAsStringDangerous();
    }

    @Test
    public void testSessionScopeNormalisation() throws Exception {
        assert(normaliseSessionScopeOrThrowErrorForTests("api.example.com").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("http://api.example.com").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("https://api.example.com").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("http://api.example.com?hello=1").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("http://api.example.com/hello").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("http://api.example.com/").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("http://api.example.com:8080").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("http://api.example.com#random2").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("api.example.com/").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("api.example.com#random").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("example.com").equals("example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("api.example.com/?hello=1&bye=2").equals("api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests("localhost").equals("localhost"));
        assert(normaliseSessionScopeOrThrowErrorForTests("localhost:8080").equals("localhost"));
        assert(normaliseSessionScopeOrThrowErrorForTests("localhost.org").equals("localhost.org"));
        assert(normaliseSessionScopeOrThrowErrorForTests("127.0.0.1").equals("127.0.0.1"));

        assert(normaliseSessionScopeOrThrowErrorForTests(".api.example.com").equals(".api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".api.example.com/").equals(".api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".api.example.com#random").equals(".api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".example.com").equals(".example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".api.example.com/?hello=1&bye=2").equals(".api.example.com"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".localhost").equals("localhost"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".localhost:8080").equals("localhost"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".localhost.org").equals(".localhost.org"));
        assert(normaliseSessionScopeOrThrowErrorForTests(".127.0.0.1").equals("127.0.0.1"));

        try {
            normaliseSessionScopeOrThrowErrorForTests("http://");
            assert(false);
        } catch (Exception e) {
            assert e.getMessage().equals("Please provide a valid sessionScope");
        }
    }

    @Test
    public void testingURLPathNormalisation() throws Exception {
        assert(normaliseURLPathOrThrowError("exists?email=john.doe%40gmail.com").equals("/exists"));
        assert(
                normaliseURLPathOrThrowError("/auth/email/exists?email=john.doe%40gmail.com").equals("/auth/email/exists")
        );
        assert(normaliseURLPathOrThrowError("exists").equals("/exists"));
        assert(normaliseURLPathOrThrowError("/exists").equals("/exists"));
        assert(normaliseURLPathOrThrowError("/exists?email=john.doe%40gmail.com").equals("/exists"));
        assert(normaliseURLPathOrThrowError("http://api.example.com").equals(""));
        assert(normaliseURLPathOrThrowError("https://api.example.com").equals(""));
        assert(normaliseURLPathOrThrowError("http://api.example.com?hello=1").equals(""));
        assert(normaliseURLPathOrThrowError("http://api.example.com/hello").equals("/hello"));
        assert(normaliseURLPathOrThrowError("http://api.example.com/").equals(""));
        assert(normaliseURLPathOrThrowError("http://api.example.com:8080").equals(""));
        assert(normaliseURLPathOrThrowError("http://api.example.com#random2").equals(""));
        assert(normaliseURLPathOrThrowError("api.example.com/").equals(""));
        assert(normaliseURLPathOrThrowError("api.example.com#random").equals(""));
        assert(normaliseURLPathOrThrowError(".example.com").equals(""));
        assert(normaliseURLPathOrThrowError("api.example.com/?hello=1&bye=2").equals(""));

        assert(normaliseURLPathOrThrowError("http://api.example.com/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("http://1.2.3.4/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("1.2.3.4/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("https://api.example.com/one/two/").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("http://api.example.com/one/two?hello=1").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("http://api.example.com/hello/").equals("/hello"));
        assert(normaliseURLPathOrThrowError("http://api.example.com/one/two/").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("http://api.example.com:8080/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("http://api.example.com/one/two#random2").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("api.example.com/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("api.example.com/one/two/#random").equals("/one/two"));
        assert(normaliseURLPathOrThrowError(".example.com/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("api.example.com/one/two?hello=1&bye=2").equals("/one/two"));

        assert(normaliseURLPathOrThrowError("/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("one/two/").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("/one").equals("/one"));
        assert(normaliseURLPathOrThrowError("one").equals("/one"));
        assert(normaliseURLPathOrThrowError("one/").equals("/one"));
        assert(normaliseURLPathOrThrowError("/one/two/").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("/one/two?hello=1").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("one/two?hello=1").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("/one/two/#random").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("one/two#random").equals("/one/two"));

        assert(normaliseURLPathOrThrowError("localhost:4000/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("127.0.0.1:4000/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("127.0.0.1/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("https://127.0.0.1:80/one/two").equals("/one/two"));
        assert(normaliseURLPathOrThrowError("/").equals(""));

        assert(normaliseURLPathOrThrowError("/.netlify/functions/api").equals("/.netlify/functions/api"));
        assert(normaliseURLPathOrThrowError("/netlify/.functions/api").equals("/netlify/.functions/api"));
        assert(
                normaliseURLPathOrThrowError("app.example.com/.netlify/functions/api").equals("/.netlify/functions/api")
        );
        assert(
                normaliseURLPathOrThrowError("app.example.com/netlify/.functions/api").equals("/netlify/.functions/api")
        );
        assert(normaliseURLPathOrThrowError("/app.example.com").equals("/app.example.com"));
    }

    @Test
    public void testURLDomainNormalisation() throws Exception {
        assert(normaliseURLDomainOrThrowError("http://api.example.com").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("https://api.example.com").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com?hello=1").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com/hello").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com/").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com:8080").equals("http://api.example.com:8080"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com#random2").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("api.example.com/").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError("api.example.com").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError("api.example.com#random").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError(".example.com").equals("https://example.com"));
        assert(normaliseURLDomainOrThrowError("api.example.com/?hello=1&bye=2").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError("localhost").equals("http://localhost"));
        assert(normaliseURLDomainOrThrowError("https://localhost").equals("https://localhost"));

        assert(normaliseURLDomainOrThrowError("http://api.example.com/one/two").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://1.2.3.4/one/two").equals("http://1.2.3.4"));
        assert(normaliseURLDomainOrThrowError("https://1.2.3.4/one/two").equals("https://1.2.3.4"));
        assert(normaliseURLDomainOrThrowError("1.2.3.4/one/two").equals("http://1.2.3.4"));
        assert(normaliseURLDomainOrThrowError("https://api.example.com/one/two/").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com/one/two?hello=1").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("http://api.example.com/one/two#random2").equals("http://api.example.com"));
        assert(normaliseURLDomainOrThrowError("api.example.com/one/two").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError("api.example.com/one/two/#random").equals("https://api.example.com"));
        assert(normaliseURLDomainOrThrowError(".example.com/one/two").equals("https://example.com"));
        assert(normaliseURLDomainOrThrowError("localhost:4000").equals("http://localhost:4000"));
        assert(normaliseURLDomainOrThrowError("127.0.0.1:4000").equals("http://127.0.0.1:4000"));
        assert(normaliseURLDomainOrThrowError("127.0.0.1").equals("http://127.0.0.1"));
        assert(normaliseURLDomainOrThrowError("https://127.0.0.1:80/").equals("https://127.0.0.1:80"));
        assert(normaliseURLDomainOrThrowError("http://localhost.org:8080").equals("http://localhost.org:8080"));
    }

    @Test
    public void testShouldDoInterceptionBasedOnUrl() throws Exception {
        // true cases without cookieDomain
        assert(shouldDoInterceptionBasedOnUrl("api.example.com", "https://api.example.com", null));
        assert(shouldDoInterceptionBasedOnUrl("http://api.example.com", "http://api.example.com", null));
        assert(shouldDoInterceptionBasedOnUrl("api.example.com", "http://api.example.com", null));
        assert(shouldDoInterceptionBasedOnUrl("https://api.example.com", "http://api.example.com", null));
        assert(
                shouldDoInterceptionBasedOnUrl("https://api.example.com:3000", "http://api.example.com:3000", null)
        );
        assert(shouldDoInterceptionBasedOnUrl("localhost:3000", "localhost:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("https://localhost:3000", "https://localhost:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("http://localhost:3000", "http://localhost:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("localhost:3000", "https://localhost:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("localhost", "https://localhost", null));
        assert(shouldDoInterceptionBasedOnUrl("http://localhost:3000", "https://localhost:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("127.0.0.1:3000", "127.0.0.1:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("https://127.0.0.1:3000", "https://127.0.0.1:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("http://127.0.0.1:3000", "http://127.0.0.1:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("127.0.0.1:3000", "https://127.0.0.1:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("http://127.0.0.1:3000", "https://127.0.0.1:3000", null));
        assert(shouldDoInterceptionBasedOnUrl("http://127.0.0.1", "https://127.0.0.1", null));

        // true cases with cookieDomain
        assert(shouldDoInterceptionBasedOnUrl("api.example.com", "", "api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("http://api.example.com", "", "http://api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("api.example.com", "", ".example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://api.example.com", "", "http://api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://api.example.com", "", "https://api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.com", "", ".api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.com", "", ".example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", ".example.com:3000"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", ".example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", "https://sub.api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("https://api.example.com:3000", "", ".api.example.com"));
        assert(shouldDoInterceptionBasedOnUrl("localhost:3000", "", "localhost:3000"));
        assert(shouldDoInterceptionBasedOnUrl("https://localhost:3000", "", ".localhost:3000"));
        assert(shouldDoInterceptionBasedOnUrl("localhost", "", "localhost"));
        assert(shouldDoInterceptionBasedOnUrl("http://a.localhost:3000", "", ".localhost:3000"));
        assert(shouldDoInterceptionBasedOnUrl("127.0.0.1:3000", "", "127.0.0.1:3000"));
        assert(shouldDoInterceptionBasedOnUrl("https://127.0.0.1:3000", "", "https://127.0.0.1:3000"));
        assert(shouldDoInterceptionBasedOnUrl("http://127.0.0.1:3000", "", "http://127.0.0.1:3000"));
        assert(shouldDoInterceptionBasedOnUrl("127.0.0.1:3000", "", "https://127.0.0.1:3000"));
        assert(shouldDoInterceptionBasedOnUrl("http://127.0.0.1:3000", "", "https://127.0.0.1:3000"));
        assert(shouldDoInterceptionBasedOnUrl("http://127.0.0.1", "", "https://127.0.0.1"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", ".com"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub.api.example.co.uk:3000", "", ".api.example.co.uk"));
        assert(shouldDoInterceptionBasedOnUrl("https://sub1.api.example.co.uk:3000", "", ".api.example.co.uk"));
        assert(shouldDoInterceptionBasedOnUrl("https://api.example.co.uk:3000", "", ".api.example.co.uk"));
        assert(shouldDoInterceptionBasedOnUrl("https://api.example.co.uk:3000", "", "api.example.co.uk"));

        // false cases with api
        assert(!shouldDoInterceptionBasedOnUrl("localhost:3001", "localhost:3000", null));
        assert(!shouldDoInterceptionBasedOnUrl("localhost:3001", "example.com", null));
        assert(!shouldDoInterceptionBasedOnUrl("localhost:3001", "localhost", null));
        assert(!shouldDoInterceptionBasedOnUrl("https://example.com", "https://api.example.com", null));
        assert(!shouldDoInterceptionBasedOnUrl("https://api.example.com", "https://a.api.example.com", null));
        assert(!shouldDoInterceptionBasedOnUrl("https://api.example.com", "https://example.com", null));
        assert(!shouldDoInterceptionBasedOnUrl("https://example.com:3001", "https://api.example.com:3001", null));
        assert(
                !shouldDoInterceptionBasedOnUrl("https://api.example.com:3002", "https://api.example.com:3001", null)
        );

        // false cases with cookieDomain
        assert(!shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", ".example.com:3001"));
        assert(!shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", "example.com"));
        assert(!shouldDoInterceptionBasedOnUrl("https://api.example.com:3000", "", ".a.api.example.com"));
        assert(!shouldDoInterceptionBasedOnUrl("https://sub.api.example.com:3000", "", "localhost"));
        assert(!shouldDoInterceptionBasedOnUrl("http://127.0.0.1:3000", "", "https://127.0.0.1:3010"));
        assert(!shouldDoInterceptionBasedOnUrl("https://sub.api.example.co.uk:3000", "", "api.example.co.uk"));
        assert(!shouldDoInterceptionBasedOnUrl("https://sub.api.example.co.uk", "", "api.example.co.uk"));

        // errors in input
        try {
            assert(shouldDoInterceptionBasedOnUrl("/some/path", "", "api.example.co.uk"));
            assert(false);
        } catch (Exception e) {
            if (!e.getMessage().equals("Please provide a valid domain name")) {
                throw e;
            }
        }
        try {
            assert(shouldDoInterceptionBasedOnUrl("/some/path", "api.example.co.uk", null));
            assert(false);
        } catch (Exception e) {
            if (!e.getMessage().equals("Please provide a valid domain name")) {
                throw e;
            }
        }
    }
}
