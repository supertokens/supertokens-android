package com.supertokens.session;

import java.net.InetAddress;
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

public class OkHttpCapabilityTest extends SessionTestFixture {
    @Test public void ownedConstructorMatchesDeclaredRuntimeCapability() throws Exception {
        if (!ownedRefreshAvailable()) return;
        assertNotNull(okhttp3.RequestBody.class.getMethod("isOneShot"));
        assertNotNull(new SuperTokensInterceptor(client));
    }

    @Test public void legacyRefreshUsesTlsDnsTrustAndPin() throws Exception { legacyTls(false); }
    @Test public void legacyRequestRejectsWrongPinWithoutMutatingSession() throws Exception { legacyTls(true); }

    private void legacyTls(boolean wrongPin) throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().addSubjectAlternativeName("session.test").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates trust = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        MockWebServer endpoint = new MockWebServer();
        endpoint.useHttps(serverTls.sslSocketFactory(), false);
        endpoint.start();
        OkHttpClient transport = null;
        try {
            String origin = "https://session.test:" + endpoint.getPort();
            assertTrue(new SuperTokens.Builder(context, origin).reconfigure().isSuccess());
            SessionSnapshot before = install("tls-legacy", "before");
            endpoint.enqueue(new MockResponse().setResponseCode(401));
            endpoint.enqueue(rotation(tokens("tls-legacy", "after")));
            endpoint.enqueue(new MockResponse().setBody("ok"));
            HeldCertificate pin = wrongPin ? new HeldCertificate.Builder().build() : certificate;
            transport = new OkHttpClient.Builder().dns(host -> Collections.singletonList(InetAddress.getByName("127.0.0.1")))
                    .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager())
                    .certificatePinner(new CertificatePinner.Builder().add("session.test", CertificatePinner.pin(pin.certificate())).build())
                    .callTimeout(5, TimeUnit.SECONDS).addInterceptor(new SuperTokensInterceptor()).build();
            try (Response response = transport.newCall(new Request.Builder().url(origin + "/protected").build()).execute()) {
                assertFalse(wrongPin);
                assertEquals(200, response.code());
            } catch (SSLPeerUnverifiedException expected) { if (!wrongPin) throw expected; }
            assertEquals(wrongPin ? 0 : 3, endpoint.getRequestCount());
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertSame(before.getOwner(), after.getOwner());
            assertEquals(wrongPin ? before.getTokens().getRefreshToken() : tokens("tls-legacy", "after").getRefreshToken(), after.getTokens().getRefreshToken());
        } finally {
            if (transport != null) {
                transport.connectionPool().evictAll();
                transport.dispatcher().executorService().shutdownNow();
            }
            endpoint.shutdown();
        }
    }
}
