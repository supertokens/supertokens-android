/*
 * Copyright (c) 2023, VRAI Labs and/or its affiliates. All rights reserved.
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

package com.supertokens.session;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.security.Permission;
import java.util.List;
import java.util.Map;

public class SuperTokensCustomHttpURLConnection extends HttpURLConnection {
    HttpURLConnection original;
    Context applicationContext;

    public SuperTokensCustomHttpURLConnection(HttpURLConnection original, Context applicationContext) {
        super(original.getURL());
        this.original = original;
        this.applicationContext = applicationContext;
    }

    @Override
    public void disconnect() {
        original.disconnect();
    }

    @Override
    public boolean usingProxy() {
        return original.usingProxy();
    }

    @Override
    public void connect() throws IOException {
        original.connect();
    }

    public String getHeaderFieldKey(int n) {
        return original.getHeaderFieldKey(n);
    }

    public void setFixedLengthStreamingMode(int contentLength) {
        original.setFixedLengthStreamingMode(contentLength);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public void setFixedLengthStreamingMode(long contentLength) {
        original.setFixedLengthStreamingMode(contentLength);
    }

    public void setChunkedStreamingMode(int chunklen) {
        original.setChunkedStreamingMode(chunklen);
    }

    public String getHeaderField(int n) {
        return original.getHeaderField(n);
    }

    public void setInstanceFollowRedirects(boolean followRedirects) {
        original.setInstanceFollowRedirects(followRedirects);
    }

    public boolean getInstanceFollowRedirects() {
        return original.getInstanceFollowRedirects();
    }

    public void setRequestMethod(String method) throws ProtocolException {
        original.setRequestMethod(method);
    }

    public String getRequestMethod() {
        return original.getRequestMethod();
    }

    public int getResponseCode() throws IOException {
        return original.getResponseCode();
    }

    public String getResponseMessage() throws IOException {
        return original.getResponseMessage();
    }

    @SuppressWarnings("deprecation")
    public long getHeaderFieldDate(String name, long Default) {
        return original.getHeaderFieldDate(name, Default);
    }

    public Permission getPermission() throws IOException {
        return original.getPermission();
    }

    public InputStream getErrorStream() {
        return original.getErrorStream();
    }

    public void setConnectTimeout(int timeout) {
        original.setConnectTimeout(timeout);
    }

    public int getConnectTimeout() {
        return original.getConnectTimeout();
    }

    public void setReadTimeout(int timeout) {
        original.setReadTimeout(timeout);
    }

    public int getReadTimeout() {
        return original.getReadTimeout();
    }

    public URL getURL() {
        return original.getURL();
    }

    public int getContentLength() {
        return original.getContentLength();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public long getContentLengthLong() {
        return original.getContentLengthLong();
    }

    public String getContentType() {
        return original.getContentType();
    }

    public String getContentEncoding() {
        return original.getContentEncoding();
    }

    public long getExpiration() {
        return original.getExpiration();
    }

    public long getDate() {
        return original.getDate();
    }

    public long getLastModified() {
        return original.getLastModified();
    }

    public String getHeaderField(String name) {
        return original.getHeaderField(name);
    }

    public Map<String, List<String>> getHeaderFields() {
        return original.getHeaderFields();
    }

    public int getHeaderFieldInt(String name, int Default) {
        return original.getHeaderFieldInt(name, Default);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public long getHeaderFieldLong(String name, long Default) {
        return original.getHeaderFieldLong(name, Default);
    }

    public Object getContent() throws IOException {
        return original.getContent();
    }

    public Object getContent(Class[] classes) throws IOException {
        return original.getContent(classes);
    }

    public InputStream getInputStream() throws IOException {
        return original.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return original.getOutputStream();
    }

    public String toString() {
        return  original.toString();
    }

    public void setDoInput(boolean doinput) {
        original.setDoInput(doinput);
    }

    public boolean getDoInput() {
        return original.getDoInput();
    }

    public void setDoOutput(boolean dooutput) {
        original.setDoOutput(dooutput);
    }

    public boolean getDoOutput() {
        return original.getDoOutput();
    }

    public void setAllowUserInteraction(boolean allowuserinteraction) {
        original.setAllowUserInteraction(allowuserinteraction);
    }

    public boolean getAllowUserInteraction() {
        return original.getAllowUserInteraction();
    }

    public void setUseCaches(boolean usecaches) {
        original.setUseCaches(usecaches);
    }

    public boolean getUseCaches() {
        return original.getUseCaches();
    }

    public void setIfModifiedSince(long ifmodifiedsince) {
        original.setIfModifiedSince(ifmodifiedsince);
    }

    public long getIfModifiedSince() {
        return original.getIfModifiedSince();
    }

    public boolean getDefaultUseCaches() {
        return original.getDefaultUseCaches();
    }

    public void setDefaultUseCaches(boolean defaultusecaches) {
        original.setDefaultUseCaches(defaultusecaches);
    }

    private boolean shouldAllowSettingAuthHeader(String value) {
        String accessToken = Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, applicationContext);
        String refreshToken = Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, applicationContext);
        if (accessToken != null && refreshToken != null && value.equals("Bearer " + accessToken)) {
            // We ignore the attempt to set the header because it matches the existing access token
            // which will get added by the SDK
            return false;
        }

        return true;
    }

    public void setRequestProperty(String key, String value, boolean force) {
        // Java considers some headers to be protected and trying to read the values in code
        // always returns null for them. To handle the case where the user sets an authorization
        // header that is the same as the access token in storage, we check for the key and value
        // and only call super if the value is different.
        if (key.equalsIgnoreCase("authorization")) {
            // If force is false it means that we should ignore the attempt if it matches the
            // existing access token
            if (!shouldAllowSettingAuthHeader(value) && !force) {
                return;
            }
        }
        original.setRequestProperty(key, value);
    }

    public void setRequestProperty(String key, String value) {
        setRequestProperty(key, value, false);
    }

    public void addRequestProperty(String key, String value) {
        // We check for this because addRequestProperty does not overwrite existing values
        if (key.equalsIgnoreCase("authorization") && !shouldAllowSettingAuthHeader(value)) {
            return;
        }

        original.addRequestProperty(key, value);
    }

    public String getRequestProperty(String key) {
        return original.getRequestProperty(key);
    }

    public Map<String, List<String>> getRequestProperties() {
        return original.getRequestProperties();
    }
}