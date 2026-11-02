package com.supertokens.upgrade;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Run each flavor in a fresh instrumentation process, never in parallel. */
public class PlatformCookieWireTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private void checkFlavor() {
        String expected = InstrumentationRegistry.getArguments().getString("expectedSdkName");
        assertTrue("Pass expectedSdkName=sdk054 or candidate",
                "sdk054".equals(expected) || "candidate".equals(expected));
        assertEquals("Installed flavor", expected, BuildConfig.SDK_NAME);
    }

    private static MockWebServer server() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        return server;
    }

    private static String origin(MockWebServer server, String host) {
        return "http://" + host + ":" + server.getPort();
    }

    private static RecordedRequest take(MockWebServer server, String path) throws Exception {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("Missing request for " + path, request);
        assertEquals(path, request.getPath());
        return request;
    }

    private static boolean hasCookie(RecordedRequest request, String expected) {
        for (String header : request.getHeaders().values("Cookie")) {
            for (String pair : header.split(";")) {
                String value = pair.trim();
                int equals = value.indexOf('=');
                if (equals < 0) continue;
                String cookieValue = value.substring(equals + 1).trim();
                if (cookieValue.length() >= 2 && cookieValue.startsWith("\"") && cookieValue.endsWith("\"")) {
                    cookieValue = cookieValue.substring(1, cookieValue.length() - 1);
                }
                if (expected.equals(value.substring(0, equals).trim() + "=" + cookieValue)) return true;
            }
        }
        return false;
    }

    private static void read(HttpURLConnection connection) throws Exception {
        try {
            assertEquals(200, connection.getResponseCode());
            InputStream body = connection.getInputStream();
            try { while (body.read() != -1) { } } finally { body.close(); }
        } finally { connection.disconnect(); }
    }

    private static void raw(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        read(connection);
    }

    private static void sdk(String url) throws Exception {
        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new URL(url),
                new SuperTokensHttpURLConnection.PreConnectCallback() {
                    @Override public void doAction(HttpURLConnection connection) {
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);
                    }
                });
        read(connection);
    }

    @Test public void loginAndRefreshUsePlatformCookies() throws Exception {
        checkFlavor();
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix = "cookie-wire-" + UUID.randomUUID() + "-";
        final List<String> names = new ArrayList<String>();
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String isolated = prefix + name;
                if (!names.contains(isolated)) names.add(isolated);
                return super.getSharedPreferences(isolated, mode);
            }
        };
        CookieHandler previous = CookieHandler.getDefault();
        MockWebServer server = null;
        try {
            SuperTokens.resetForTests();
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
            server = server();
            String root = origin(server, "127.0.0.1");
            new SuperTokens.Builder(context, root).tokenTransferMethod("cookie").build();
            String front = Base64.encodeToString(new JSONObject().put("uid", "cookie-user")
                    .put("ate", 4102444800000L).put("up", new JSONObject())
                    .toString().getBytes(UTF8), Base64.NO_WRAP);
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok")
                    .addHeader("front-token", front).addHeader("anti-csrf", "csrf-login")
                    .addHeader("Set-Cookie", "sAccessToken=access-login; Path=/; HttpOnly")
                    .addHeader("Set-Cookie", "sRefreshToken=refresh-login; Path=/auth/session/refresh; HttpOnly"));
            sdk(root + "/auth/login");
            RecordedRequest login = take(server, "/auth/login");
            assertFalse(hasCookie(login, "sAccessToken=access-login"));
            assertFalse(hasCookie(login, "sRefreshToken=refresh-login"));
            assertTrue("Login must establish session", SuperTokens.doesSessionExist(context));
            assertEquals("cookie-user", SuperTokens.getUserId(context));
            assertNull("Cookie login must not create header access token", SuperTokens.getAccessToken(context));

            server.enqueue(new MockResponse().setBody("ok"));
            sdk(root + "/protected");
            RecordedRequest protectedRequest = take(server, "/protected");
            assertTrue("Protected request needs login access cookie", hasCookie(protectedRequest, "sAccessToken=access-login"));
            assertFalse("Refresh cookie escaped its path", hasCookie(protectedRequest, "sRefreshToken=refresh-login"));
            assertNull(protectedRequest.getHeader("Authorization"));
            assertEquals("csrf-login", protectedRequest.getHeader("anti-csrf"));

            server.enqueue(new MockResponse().setBody("ok").addHeader("front-token", front)
                    .addHeader("anti-csrf", "csrf-refresh")
                    .addHeader("Set-Cookie", "sAccessToken=access-refresh; Path=/; HttpOnly")
                    .addHeader("Set-Cookie", "sRefreshToken=refresh-next; Path=/auth/session/refresh; HttpOnly"));
            assertTrue("Refresh failed", SuperTokens.attemptRefreshingSession(context));
            RecordedRequest refresh = take(server, "/auth/session/refresh");
            assertEquals("POST", refresh.getMethod());
            assertTrue("Refresh must carry login refresh cookie", hasCookie(refresh, "sRefreshToken=refresh-login"));
            assertNull(refresh.getHeader("Authorization"));
            assertEquals("csrf-login", refresh.getHeader("anti-csrf"));

            server.enqueue(new MockResponse().setBody("ok"));
            sdk(root + "/protected");
            RecordedRequest next = take(server, "/protected");
            assertTrue("Protected request must carry rotated access cookie", hasCookie(next, "sAccessToken=access-refresh"));
            assertFalse(hasCookie(next, "sRefreshToken=refresh-next"));
            assertNull(next.getHeader("Authorization"));
            assertEquals("csrf-refresh", next.getHeader("anti-csrf"));
            assertEquals(4, server.getRequestCount());
        } finally {
            CookieHandler.setDefault(previous);
            try { if (server != null) server.shutdown(); }
            finally {
                SuperTokens.resetForTests();
                for (String name : names) assertTrue(target.getSharedPreferences(name, 0).edit().clear().commit());
            }
        }
    }

    @Test public void domainAndSecureCookiesDoNotEscapeTheirScopeOnHttp() throws Exception {
        checkFlavor();
        CookieHandler previous = CookieHandler.getDefault();
        MockWebServer server = null;
        try {
            CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            CookieHandler.setDefault(manager);
            server = server();
            String localhost = origin(server, "localhost");
            String ip = origin(server, "127.0.0.1");
            server.enqueue(new MockResponse().setBody("ok")
                    .addHeader("Set-Cookie", "domainProbe=local; Domain=127.0.0.1; Path=/")
                    .addHeader("Set-Cookie", "secureProbe=secret; Secure; Path=/"));
            raw(ip + "/seed");
            take(server, "/seed");
            boolean secureStored = false;
            for (HttpCookie cookie : manager.getCookieStore().getCookies()) {
                if ("secureProbe".equals(cookie.getName()) && cookie.getSecure()) secureStored = true;
            }
            assertTrue("Secure cookie must be accepted before testing HTTP suppression", secureStored);
            server.enqueue(new MockResponse().setBody("ok"));
            raw(ip + "/same");
            RecordedRequest same = take(server, "/same");
            assertTrue("Matching domain must receive its cookie", hasCookie(same, "domainProbe=local"));
            assertFalse("Secure cookie escaped over HTTP", hasCookie(same, "secureProbe=secret"));
            server.enqueue(new MockResponse().setBody("ok"));
            raw(localhost + "/foreign");
            RecordedRequest foreign = take(server, "/foreign");
            assertFalse("Cookie escaped its domain", hasCookie(foreign, "domainProbe=local"));
            assertFalse(hasCookie(foreign, "secureProbe=secret"));
            assertEquals(3, server.getRequestCount());
        } finally {
            CookieHandler.setDefault(previous);
            if (server != null) server.shutdown();
        }
    }

    // MockWebServer 3.3 cannot parse TLS 1.3 handshake metadata.
    private static final class Tls12SocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory delegate;

        Tls12SocketFactory(javax.net.ssl.SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private java.net.Socket tls12(java.net.Socket socket) {
            ((javax.net.ssl.SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket() throws java.io.IOException {
            return tls12(delegate.createSocket());
        }
        @Override public java.net.Socket createSocket(java.net.Socket socket, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return tls12(delegate.createSocket(socket, host, port, autoClose));
        }
        @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return tls12(delegate.createSocket(host, port));
        }
        @Override public java.net.Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws java.io.IOException {
            return tls12(delegate.createSocket(host, port, local, localPort));
        }
        @Override public java.net.Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return tls12(delegate.createSocket(host, port));
        }
        @Override public java.net.Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
                throws java.io.IOException {
            return tls12(delegate.createSocket(host, port, local, localPort));
        }
    }
    @Test public void secureLoginAndRefreshUseHttpsCookies() throws Exception {
        checkFlavor();
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix = "cookie-tls-" + UUID.randomUUID() + "-";
        final List<String> names = new ArrayList<String>();
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String isolated = prefix + name;
                if (!names.contains(isolated)) names.add(isolated);
                return super.getSharedPreferences(isolated, mode);
            }
        };
        CookieHandler previousCookies = CookieHandler.getDefault();
        javax.net.ssl.SSLSocketFactory previousSockets = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        javax.net.ssl.HostnameVerifier previousVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
        MockWebServer tls = null;
        MockWebServer http = null;
        try {
            Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
            java.security.cert.CertificateFactory certificates = java.security.cert.CertificateFactory.getInstance("X.509");
            InputStream certInput = testContext.getAssets().open("cookie-test-only-cert.pem");
            java.security.cert.X509Certificate cert;
            try { cert = (java.security.cert.X509Certificate) certificates.generateCertificate(certInput); }
            finally { certInput.close(); }
            try { cert.checkValidity(); }
            catch (java.security.cert.CertificateException error) {
                throw new IllegalStateException("Test TLS certificate valid from " + cert.getNotBefore()
                        + " until " + cert.getNotAfter() + "; device time is " + new java.util.Date(), error);
            }
            InputStream keyInput = testContext.getAssets().open("cookie-test-only-key.b64");
            java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = keyInput.read(buffer)) != -1) encoded.write(buffer, 0, count);
            } finally { keyInput.close(); }
            java.security.PrivateKey key = java.security.KeyFactory.getInstance("RSA").generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(Base64.decode(encoded.toByteArray(), Base64.DEFAULT)));
            char[] password = "test-only".toCharArray();
            java.security.KeyStore identity = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            identity.load(null, null);
            identity.setKeyEntry("localhost", key, password, new java.security.cert.Certificate[]{cert});
            javax.net.ssl.KeyManagerFactory keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keys.init(identity, password);
            javax.net.ssl.SSLContext serverContext = javax.net.ssl.SSLContext.getInstance("TLS");
            serverContext.init(keys.getKeyManagers(), null, null);
            java.security.KeyStore roots = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            roots.load(null, null);
            roots.setCertificateEntry("cookie-test-only", cert);
            javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trust.init(roots);
            javax.net.ssl.SSLContext clientContext = javax.net.ssl.SSLContext.getInstance("TLS");
            clientContext.init(null, trust.getTrustManagers(), null);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(clientContext.getSocketFactory()));
            assertSame("Hostname verification must remain unchanged", previousVerifier,
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier());
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
            SuperTokens.resetForTests();
            tls = new MockWebServer();
            tls.useHttps(serverContext.getSocketFactory(), false);
            tls.start(InetAddress.getByName("127.0.0.1"), 0);
            http = server();
            String root = "https://127.0.0.1:" + tls.getPort();
            new SuperTokens.Builder(context, root).tokenTransferMethod("cookie").build();
            String front = Base64.encodeToString(new JSONObject().put("uid", "tls-user")
                    .put("ate", 4102444800000L).put("up", new JSONObject())
                    .toString().getBytes(UTF8), Base64.NO_WRAP);
            tls.enqueue(new MockResponse().setBody("ok").addHeader("front-token", front)
                    .addHeader("anti-csrf", "tls-csrf")
                    .addHeader("Set-Cookie", "sAccessToken=tls-access; Secure; HttpOnly; Path=/")
                    .addHeader("Set-Cookie", "sRefreshToken=tls-refresh; Secure; HttpOnly; Path=/auth/session/refresh"));
            sdk(root + "/auth/login");
            RecordedRequest login = take(tls, "/auth/login");
            assertFalse(hasCookie(login, "sAccessToken=tls-access"));
            assertFalse(hasCookie(login, "sRefreshToken=tls-refresh"));
            assertTrue(SuperTokens.doesSessionExist(context));
            assertEquals("tls-user", SuperTokens.getUserId(context));
            assertNull(SuperTokens.getAccessToken(context));
            tls.enqueue(new MockResponse().setBody("ok"));
            sdk(root + "/protected");
            RecordedRequest protectedRequest = take(tls, "/protected");
            assertTrue(hasCookie(protectedRequest, "sAccessToken=tls-access"));
            assertFalse(hasCookie(protectedRequest, "sRefreshToken=tls-refresh"));
            assertNull(protectedRequest.getHeader("Authorization"));
            assertEquals("tls-csrf", protectedRequest.getHeader("anti-csrf"));
            http.enqueue(new MockResponse().setBody("ok"));
            raw(origin(http, "127.0.0.1") + "/auth/session/refresh");
            RecordedRequest insecure = take(http, "/auth/session/refresh");
            assertFalse(hasCookie(insecure, "sAccessToken=tls-access"));
            assertFalse(hasCookie(insecure, "sRefreshToken=tls-refresh"));
            tls.enqueue(new MockResponse().setBody("ok").addHeader("front-token", front)
                    .addHeader("anti-csrf", "tls-csrf-next")
                    .addHeader("Set-Cookie", "sAccessToken=tls-access-next; Secure; HttpOnly; Path=/")
                    .addHeader("Set-Cookie", "sRefreshToken=tls-refresh-next; Secure; HttpOnly; Path=/auth/session/refresh"));
            assertTrue(SuperTokens.attemptRefreshingSession(context));
            RecordedRequest refresh = take(tls, "/auth/session/refresh");
            assertEquals("POST", refresh.getMethod());
            assertTrue(hasCookie(refresh, "sAccessToken=tls-access"));
            assertTrue(hasCookie(refresh, "sRefreshToken=tls-refresh"));
            assertNull(refresh.getHeader("Authorization"));
            assertEquals("tls-csrf", refresh.getHeader("anti-csrf"));
            tls.enqueue(new MockResponse().setBody("ok"));
            sdk(root + "/protected");
            RecordedRequest next = take(tls, "/protected");
            assertTrue(hasCookie(next, "sAccessToken=tls-access-next"));
            assertFalse(hasCookie(next, "sRefreshToken=tls-refresh-next"));
            assertNull(next.getHeader("Authorization"));
            assertEquals("tls-csrf-next", next.getHeader("anti-csrf"));
            assertEquals(4, tls.getRequestCount());
            assertEquals(1, http.getRequestCount());
            assertSame(previousVerifier, javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier());
        } finally {
            CookieHandler.setDefault(previousCookies);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(previousSockets);
            try { if (tls != null) tls.shutdown(); }
            finally {
                try { if (http != null) http.shutdown(); }
                finally {
                    SuperTokens.resetForTests();
                    for (String name : names) assertTrue(target.getSharedPreferences(name, 0).edit().clear().commit());
                }
            }
        }
    }
}
