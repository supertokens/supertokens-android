package com.supertokens.session;

import android.content.Context;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/** Shared test artifact: a fresh loader is a fresh legacy process. */
public final class Legacy054Harness implements Closeable {
    private final URLClassLoader loader;
    private final Object driver;
    private boolean closed;

    public Legacy054Harness(Context context) throws Exception {
        loader = new URLClassLoader(new URL[]{artifact("legacy054.driver"), artifact("legacy054.jar")},
                Legacy054Harness.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.supertokens.")) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                // Missing legacy classes must not silently resolve to candidate code.
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        };
        try {
            driver = loader.loadClass("com.supertokens.session.Legacy054Driver")
                    .getConstructor(Context.class).newInstance(context);
        } catch (Exception | Error failure) {
            loader.close();
            throw failure;
        }
    }

    private static URL artifact(String property) throws IOException {
        String path = System.getProperty(property);
        if (path == null || !new File(path).exists()) {
            throw new IOException("Missing " + property + "; run :app:legacy054Test");
        }
        return new File(path).toURI().toURL();
    }

    public Class<?> sdkClass() throws ClassNotFoundException {
        return loader.loadClass("com.supertokens.session.SuperTokens");
    }

    public Object invoke(String method, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        if (closed) throw new IllegalStateException("Legacy harness is closed");
        try {
            return driver.getClass().getMethod(method, parameterTypes).invoke(driver, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    public Object invoke(String method) throws Exception { return invoke(method, new Class<?>[0]); }

    public void build(String apiDomain, String transferMethod) throws Exception {
        invoke("build", new Class<?>[]{String.class, String.class}, apiDomain, transferMethod);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> request(String url) throws Exception {
        return (Map<String, Object>) invoke("request", new Class<?>[]{String.class}, url);
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { ((Closeable) driver).close(); } finally { loader.close(); }
    }
}
