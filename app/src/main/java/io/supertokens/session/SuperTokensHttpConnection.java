package io.supertokens.session;

import android.os.Build;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.security.Permission;
import java.util.*;

public class SuperTokensHttpConnection extends HttpURLConnection {
    private HttpURLConnection connection;
    private ArrayList<Function> connectionOperations = new ArrayList<Function>();

    SuperTokensHttpConnection(URL url) throws IOException {
        super(url);
        connection =(HttpURLConnection) url.openConnection();
    }

    // URLConnection methods

    @Override
    public void connect() throws IOException {

    }

    public void setConnectTimeout(int timeout) {
        final int finalTimeout = timeout;
        connectionOperations.add(new Function() {
            @Override
            public void doBody(HttpURLConnection con) {
                con.setConnectTimeout(finalTimeout);
            }
        });
        connection.setConnectTimeout(finalTimeout);
    }

    public int getConnectTimeout() {
        return connection.getConnectTimeout();
    }

    public void setReadTimeout(int timeout) {
        connection.setReadTimeout(timeout);
    }

    public int getReadTimeout() {
        return connection.getReadTimeout();
    }

    public URL getURL() {
        return connection.getURL();
    }

    public int getContentLength() {
        return connection.getContentLength();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public long getContentLengthLong() {
        return connection.getContentLengthLong();
    }

    public String getContentType() {
        return connection.getContentType();
    }

    public String getContentEncoding() {
        return connection.getContentEncoding();
    }

    public long getExpiration() {
        return connection.getExpiration();
    }

    public long getDate() {
        return connection.getDate();
    }

    public long getLastModified() {
        return connection.getLastModified();
    }

    public String getHeaderField(String name) {
        return connection.getHeaderField(name);
    }

    public Map<String, List<String>> getHeaderFields() {
        return connection.getHeaderFields();
    }

    public int getHeaderFieldInt(String name, int Default) {
        return connection.getHeaderFieldInt(name, Default);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public long getHeaderFieldLong(String name, long Default) {
        return connection.getHeaderFieldLong(name, Default);
    }

    @Override
    public void disconnect() {

    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    public Object getContent() throws IOException {
        return connection.getContent();
    }

    public Object getContent(Class[] classes) throws IOException {
        return connection.getContent(classes);
    }


    public InputStream getInputStream() throws IOException {
        return connection.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return connection.getOutputStream();
    }

    public String toString() {
        return connection.toString();
    }

    public void setDoInput(boolean doinput) {
        connection.setDoInput(doinput);
    }

    public boolean getDoInput() {
        return connection.getDoInput();
    }

    public void setDoOutput(boolean dooutput) {
        connection.setDoOutput(dooutput);
    }

    public boolean getDoOutput() {
        return connection.getDoOutput();
    }

    public void setAllowUserInteraction(boolean allowuserinteraction) {
        connection.setAllowUserInteraction(allowuserinteraction);
    }

    public boolean getAllowUserInteraction() {
        return connection.getAllowUserInteraction();
    }

    public void setUseCaches(boolean usecaches) {
        connection.setUseCaches(usecaches);
    }

    public boolean getUseCaches() {
        return connection.getUseCaches();
    }

    public void setIfModifiedSince(long ifmodifiedsince) {
        connection.setIfModifiedSince(ifmodifiedsince);
    }

    public long getIfModifiedSince() {
        return connection.getIfModifiedSince();
    }

    public boolean getDefaultUseCaches() {
        return connection.getDefaultUseCaches();
    }

    public void setDefaultUseCaches(boolean defaultusecaches) {
        connection.setDefaultUseCaches(defaultusecaches);
    }

    public void setRequestProperty(String key, String value) {
        connection.setRequestProperty(key, value);
    }

    public void addRequestProperty(String key, String value) {
        connection.addRequestProperty(key, value);
    }

    public String getRequestProperty(String key) {
        return  connection.getRequestProperty(key);
    }

    public Map<String,List<String>> getRequestProperties() {
        return connection.getRequestProperties();
    }

    // END URLConnection methods

    // HttpURLConnection methods

    /**
     * Returns the key for the {@code n}<sup>th</sup> header field.
     * Some implementations may treat the {@code 0}<sup>th</sup>
     * header field as special, i.e. as the status line returned by the HTTP
     * server. In this case, {@link #getHeaderField(int) getHeaderField(0)} returns the status
     * line, but {@code getHeaderFieldKey(0)} returns null.
     *
     * @param   n   an index, where {@code n >=0}.
     * @return  the key for the {@code n}<sup>th</sup> header field,
     *          or {@code null} if the key does not exist.
     */
    public String getHeaderFieldKey (int n) {
        return null;
    }

    public void setFixedLengthStreamingMode (int contentLength) {
        connection.setFixedLengthStreamingMode(contentLength);
    }

    public void setFixedLengthStreamingMode(long contentLength) {
        connection.setFixedLengthStreamingMode(contentLength);
    }

    public void setChunkedStreamingMode (int chunklen) {
        connection.setChunkedStreamingMode(chunklen);
    }

    public String getHeaderField(int n) {
        return null;
    }

    public void setInstanceFollowRedirects(boolean followRedirects) {
        connection.setInstanceFollowRedirects(followRedirects);
    }

    public boolean getInstanceFollowRedirects() {
        return connection.getInstanceFollowRedirects();
    }

    public void setRequestMethod(String method) throws ProtocolException {
        connection.setRequestMethod(method);
    }

    public String getRequestMethod() {
        return connection.getRequestMethod();
    }

    public int getResponseCode() throws IOException {
        return connection.getResponseCode();
    }

    public String getResponseMessage() throws IOException {
        return connection.getResponseMessage();
    }

    @SuppressWarnings("deprecation")
    public long getHeaderFieldDate(String name, long Default) {
        return connection.getHeaderFieldDate(name, Default);
    }

    public Permission getPermission() throws IOException {
        return connection.getPermission();
    }

    public InputStream getErrorStream() {
        return connection.getErrorStream();
    }


    private interface Function {
        void doBody(HttpURLConnection connection);
    }
}
