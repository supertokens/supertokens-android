package com.supertokens.session;

import okhttp3.Request;

/** Keeps optional typed-tag linkage out of the historical interceptor path. */
final class OkHttpSessionTags {
    private static final Reader READER = selectReader();

    private OkHttpSessionTags() {}

    static SessionRequestTag get(Request request) {
        return READER.get(request);
    }

    private static Reader selectReader() {
        Request probe = new Request.Builder().url("http://localhost/").build();
        try {
            TypedReader reader = new TypedReader();
            reader.get(probe);
            return reader;
        } catch (NoSuchMethodError unsupportedTypedTags) {
            // Only the synthetic capability probe can select the historical implementation.
            return new Reader() {
                @Override SessionRequestTag get(Request request) {
                    Object tag = request.tag();
                    return tag instanceof SessionRequestTag ? (SessionRequestTag) tag : null;
                }
            };
        }
    }

    private abstract static class Reader {
        abstract SessionRequestTag get(Request request);
    }

    private static final class TypedReader extends Reader {
        @Override SessionRequestTag get(Request request) {
            return request.tag(SessionRequestTag.class);
        }
    }
}
