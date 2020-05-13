/*
 * Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
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

package io.supertokens.session.android;

import android.content.SharedPreferences;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MockSharedPrefs implements SharedPreferences {

    private final HashMap<String, Object> preferenceMap;
    private final MockSharedPreferenceEditor preferenceEditor;

    public MockSharedPrefs() {
        preferenceMap = new HashMap<>();
        preferenceEditor = new MockSharedPreferenceEditor(preferenceMap);
    }

    @Override
    public Map<String, ?> getAll() {
        return preferenceMap;
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        return (String) preferenceMap.get(key);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        return (Set<String>) preferenceMap.get(key);
    }

    @Override
    public int getInt(String key, int defValue) {
        return (int) preferenceMap.get(key);
    }

    @Override
    public long getLong(String key, long defValue) {
        return (long) preferenceMap.get(key);
    }

    @Override
    public float getFloat(String key, float defValue) {
        return (float) preferenceMap.get(key);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return (boolean) preferenceMap.get(key);
    }

    @Override
    public boolean contains(String key) {
        return preferenceMap.containsKey(key);
    }

    @Override
    public Editor edit() {
        return preferenceEditor;
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {

    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {

    }

    public static class MockSharedPreferenceEditor implements Editor {

        private final HashMap<String, Object> preferenceMap;

        public MockSharedPreferenceEditor(final HashMap<String, Object> preferenceMap) {
            this.preferenceMap = preferenceMap;
        }

        @Override
        public Editor putString(final String key, @Nullable final String value) {
            preferenceMap.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(final String key, @Nullable final Set<String> set) {
            preferenceMap.put(key, set);
            return this;
        }

        @Override
        public Editor putInt(final String key, final int value) {
            preferenceMap.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(final String key, final long value) {
            preferenceMap.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(final String key, final float value) {
            preferenceMap.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(final String key, final boolean value) {
            preferenceMap.put(key, value);
            return this;
        }

        @Override
        public Editor remove(final String key) {
            preferenceMap.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            preferenceMap.clear();
            return this;
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
            // Nothing to do, everything is saved in memory.
        }
    }
}
