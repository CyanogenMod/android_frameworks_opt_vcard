/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.vcard;

import java.util.Collection;

/**
 * The {@link VCardInterpreter} implementation which aggregates more than one
 * {@link VCardInterpreter} objects and make a user object treat them as one
 * {@link VCardInterpreter} object.
 * @deprecated {@link VCardParser} has native support for multiple interpreter now.
 */
public final class VCardInterpreterCollection implements VCardInterpreter {
    private final Collection<VCardInterpreter> mInterpreterCollection;

    public VCardInterpreterCollection(Collection<VCardInterpreter> interpreterCollection) {
        mInterpreterCollection = interpreterCollection;
    }

    public Collection<VCardInterpreter> getCollection() {
        return mInterpreterCollection;
    }

    @Override
    public void onVCardStarted() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.onVCardStarted();
        }
    }

    @Override
    public void onVCardEnded() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.onVCardEnded();
        }
    }

    @Override
    public void onEntryStarted() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.onEntryStarted();
        }
    }

    @Override
    public void onEntryEnded() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.onEntryEnded();
        }
    }

    @Override
    public void onPropertyCreated(VCardProperty property) {
        for (VCardInterpreter interpreter : mInterpreterCollection) {
            interpreter.onPropertyCreated(property);
        }
    }
}
