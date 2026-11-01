package com.supertokens.session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureIntegrityTest {
    private byte[] resource(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/upgrade/" + name)) {
            assertNotNull("Missing capture resource", input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private void verify(byte[] bytes, String expected) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", value & 255));
        assertTrue("Sanitized capture SHA-256 mismatch", expected.equals(digest.toString()));
    }

    private JSONArray cases() throws Exception {
        return new JSONObject(new String(resource("capture-provenance.json"), StandardCharsets.UTF_8)).getJSONArray("cases");
    }

    @Test public void all45FixturesMatchUniqueManifestEntries() throws Exception {
        JSONArray cases = cases();
        Set<String> paths = new HashSet<>();
        int coherent = 0, stale = 0, missing = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject row = cases.getJSONObject(i);
            switch (row.getString("originalEvidence")) {
                case "coherent": coherent++; break;
                case "stale": stale++; break;
                case "missing": missing++; break;
                default: fail("Unexpected fingerprint classification");
            }
            JSONObject files = row.getJSONObject("files");
            for (java.util.Iterator<String> names = files.keys(); names.hasNext();) {
                String name = names.next();
                String path = row.getString("folder") + "/" + name + ".xml";
                assertTrue("Duplicate manifest entry", paths.add(path));
                JSONObject file = files.getJSONObject(name);
                assertTrue(file.getString("sourceSha256").matches("[0-9a-f]{64}"));
                verify(resource(path), file.getString("sanitizedSha256"));
            }
        }
        assertEquals(45, paths.size());
        assertEquals(12, coherent);
        assertEquals(7, stale);
        assertEquals(1, missing);
    }

    private void rejects(byte[] bytes, String expected) throws Exception {
        try { verify(bytes, expected); } catch (AssertionError correctRejection) { return; }
        fail("Corrupted fixture/hash was accepted");
    }

    @Test public void alteredFixtureBytesAndManifestHashesFailVerification() throws Exception {
        JSONArray cases = cases();
        int rejected = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject row = cases.getJSONObject(i);
            JSONObject files = row.getJSONObject("files");
            for (java.util.Iterator<String> names = files.keys(); names.hasNext();) {
                String name = names.next();
                byte[] original = resource(row.getString("folder") + "/" + name + ".xml");
                String expected = files.getJSONObject(name).getString("sanitizedSha256");
                byte[] changed = original.clone();
                changed[changed.length - 1] ^= 1;
                rejects(changed, expected);
                rejects(original, (expected.startsWith("0") ? "1" : "0") + expected.substring(1));
                rejected += 2;
            }
        }
        assertEquals(90, rejected);
    }
}
