package com.example.example;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

final class UpgradeFixture implements AutoCloseable {
    interface Exchange { JSONObject post(String path, JSONObject body) throws Exception; }
    private final Exchange exchange;
    private String fixtureId;
    String userId;

    private UpgradeFixture(Exchange exchange) { this.exchange = exchange; }

    static UpgradeFixture open(String domain) throws Exception {
        return open((path, body) -> {
            OkHttpClient client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build();
            try (Response response = client.newCall(new Request.Builder().url(domain + path)
                    .post(RequestBody.create(MediaType.parse("application/json"), body.toString())).build()).execute()) {
                if (response.code() != 200 || response.body() == null) {
                    throw new IllegalStateException("Upgrade fixture " + path + " HTTP " + response.code());
                }
                return new JSONObject(response.body().string());
            } finally {
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdownNow();
            }
        });
    }

    static UpgradeFixture open(Exchange exchange) throws Exception {
        UpgradeFixture fixture = new UpgradeFixture(exchange);
        try {
            JSONObject created = exchange.post("/test/email-change-owner", new JSONObject()
                    .put("recipe", "google").put("email", "upgrade-" + UUID.randomUUID() + "@example.com"));
            fixture.fixtureId = created.getString("fixtureId");
            if (fixture.fixtureId.isEmpty()) throw new IllegalStateException("Empty fixture owner receipt");
            fixture.userId = created.getString("userId");
            if (fixture.userId.isEmpty()) throw new IllegalStateException("Empty fixture user receipt");
            System.out.println("LIVE_UPGRADE fixtureOwner=" + fixture.fixtureId);
            return fixture;
        } catch (Exception failure) {
            if (fixture.fixtureId != null && !fixture.fixtureId.isEmpty()) {
                try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            } else {
                System.err.println("LIVE_UPGRADE unresolved creation: no owner receipt; backend reconciliation required");
            }
            throw failure;
        }
    }

    @Override public void close() throws Exception {
        JSONObject receipt = exchange.post("/test/cleanup-owner", new JSONObject().put("fixtureId", fixtureId));
        if (!receipt.getBoolean("cleaned")) throw new IllegalStateException("Upgrade fixture cleanup not acknowledged: " + fixtureId);
        System.out.println("LIVE_UPGRADE cleanupOwner=" + fixtureId + " cleaned=true");
    }
}
