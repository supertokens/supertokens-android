package com.supertokens.session;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionTlsCompatibilityTest extends SessionTestFixture {
    @Test public void strictRefreshPreservesDnsTrustAndCertificatePin() throws Exception {
        tls(false);
    }

    @Test public void strictRefreshCannotBypassBadCertificatePin() throws Exception {
        tls(true);
    }

    private void tls(boolean rejectPin) throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().addSubjectAlternativeName("session.test").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        MockWebServer tlsServer = new MockWebServer();
        tlsServer.useHttps(serverTls.sslSocketFactory(), false);
        tlsServer.start();
        String origin = "https://session.test:" + tlsServer.getPort();
        OkHttpClient outer = null;
        try {
            assertTrue(new SuperTokens.Builder(context, origin).reconfigure().isSuccess());
            SessionSnapshot before = install("tls", "before");
            tlsServer.enqueue(new MockResponse().setResponseCode(401));
            tlsServer.enqueue(rotation(tokens("tls", "after")));
            tlsServer.enqueue(new MockResponse().setBody("accepted"));
            OkHttpClient trusted = new OkHttpClient.Builder()
                    .dns(host -> Collections.singletonList(InetAddress.getByName("127.0.0.1")))
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager())
                    .callTimeout(5, TimeUnit.SECONDS).build();
            HeldCertificate pinned = rejectPin ? new HeldCertificate.Builder().build() : certificate;
            OkHttpClient base = trusted.newBuilder().certificatePinner(new CertificatePinner.Builder()
                    .add("session.test", CertificatePinner.pin(pinned.certificate())).build()).build();
            outer = trusted.newBuilder().addInterceptor(new SuperTokensInterceptor(base)).build();
            try (Response response = outer.newCall(new Request.Builder().url(origin + "/profile").build()).execute()) {
                assertFalse("bad pin must fail refresh", rejectPin);
                assertEquals(200, response.code());
            } catch (SSLPeerUnverifiedException error) {
                if (!rejectPin) throw error;
            }
            assertEquals(rejectPin ? 1 : 3, tlsServer.getRequestCount());
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertSame(before.getOwner(), after.getOwner());
            assertEquals(rejectPin ? before.getTokens().getRefreshToken() : tokens("tls", "after").getRefreshToken(),
                    after.getTokens().getRefreshToken());
        } finally {
            if (outer != null) {
                outer.connectionPool().evictAll();
                outer.dispatcher().executorService().shutdownNow();
            }
            tlsServer.shutdown();
        }
    }

    @Test public void strictRefreshUsesConfiguredHttpProxy() throws Exception {
        String origin = "http://session-proxy.invalid";
        assertTrue(new SuperTokens.Builder(context, origin).reconfigure().isSuccess());
        install("proxy", "before");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(rotation(tokens("proxy", "after")));
        server.enqueue(new MockResponse().setBody("accepted"));
        OkHttpClient base = new OkHttpClient.Builder().proxy(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", server.getPort()))).callTimeout(5, TimeUnit.SECONDS).build();
        OkHttpClient transport = base.newBuilder().addInterceptor(new SuperTokensInterceptor(base)).build();
        try (Response response = transport.newCall(new Request.Builder().url(origin + "/profile").build()).execute()) {
            assertEquals(200, response.code());
            assertEquals(3, server.getRequestCount());
            server.takeRequest(1, TimeUnit.SECONDS);
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getRequestLine().contains("/auth/session/refresh"));
        } finally {
            transport.connectionPool().evictAll();
            transport.dispatcher().executorService().shutdownNow();
        }
    }
}
