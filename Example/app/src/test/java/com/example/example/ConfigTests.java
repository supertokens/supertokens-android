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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.net.MalformedURLException;

import io.supertokens.session.NormalisedURLDomain;
import io.supertokens.session.NormalisedURLPath;
import io.supertokens.session.Utils;

public class ConfigTests {

    private String normaliseURLPathOrThrowError(String input) throws MalformedURLException {
        return new NormalisedURLPath(input).getAsStringDangerous();
    }

    private  String normaliseURLDomainOrThrowError(String input) throws MalformedURLException {
        return new NormalisedURLDomain(input).getAsStringDangerous();
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
}
