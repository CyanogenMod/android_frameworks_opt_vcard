/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.vcard.tests;

import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardInterpreter;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.test.AndroidTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VCardEntryTests extends AndroidTestCase {
    private class MockVCardEntryHandler implements VCardEntryHandler {
        private List<VCardEntry> mEntries = new ArrayList<VCardEntry>();
        private boolean mOnStartCalled;
        private boolean mOnEndCalled;

        @Override
        public void onStart() {
            assertFalse(mOnStartCalled);
            mOnStartCalled = true;
        }

        @Override
        public void onEntryCreated(VCardEntry entry) {
            assertTrue(mOnStartCalled);
            assertFalse(mOnEndCalled);
            mEntries.add(entry);
        }

        @Override
        public void onEnd() {
            assertTrue(mOnStartCalled);
            assertFalse(mOnEndCalled);
            mOnEndCalled = true;
        }

        public List<VCardEntry> getEntries() {
            return mEntries;
        }
    }

    /**
     * Tests VCardEntry and related clasess can handle nested classes given
     * {@link VCardInterpreter} is called appropriately.
     *
     * This test manually calls VCardInterpreter's callback mechanism and checks
     * {@link VCardEntryConstructor} constructs {@link VCardEntry} per given calls.
     *
     * Intended vCard is as follows:
     * <code>
     * BEGIN:VCARD
     * N:test1
     * BEGIN:VCARD
     * N:test2
     * END:VCARD
     * TEL:1
     * END:VCARD
     * </code>
     */
    public void testNestHandling() {
        VCardEntryConstructor entryConstructor = new VCardEntryConstructor();
        MockVCardEntryHandler entryHandler = new MockVCardEntryHandler();
        entryConstructor.addEntryHandler(entryHandler);

        entryConstructor.start();
        entryConstructor.startEntry();
        entryConstructor.startProperty();
        entryConstructor.propertyName(VCardConstants.PROPERTY_N);
        entryConstructor.propertyValues(Arrays.asList("test1"));
        entryConstructor.endProperty();

        entryConstructor.startEntry();  // begin nest
        entryConstructor.startProperty();
        entryConstructor.propertyName(VCardConstants.PROPERTY_N);
        entryConstructor.propertyValues(Arrays.asList("test2"));
        entryConstructor.endProperty();
        entryConstructor.endEntry();  // end nest

        entryConstructor.startProperty();
        entryConstructor.propertyName(VCardConstants.PROPERTY_TEL);
        entryConstructor.propertyValues(Arrays.asList("1"));
        entryConstructor.endProperty();
        entryConstructor.endEntry();
        entryConstructor.end();

        List<VCardEntry> entries = entryHandler.getEntries();
        assertEquals(2, entries.size());
        VCardEntry parent = entries.get(1);
        VCardEntry child = entries.get(0);
        assertEquals("test1", parent.getDisplayName());
        assertEquals("test2", child.getDisplayName());
        List<VCardEntry.PhoneData> phoneList = parent.getPhoneList();
        assertNotNull(phoneList);
        assertEquals(1, phoneList.size());
        assertEquals("1", phoneList.get(0).data);
    }

    /**
     * Tests that VCardEntry emits correct insert operation for name field.
     */
    public void testConstructInsertOperationsInsertName() {
        VCardEntry entry = new VCardEntry();
        VCardEntry.Property property = new VCardEntry.Property();
        property.setPropertyName("N");
        property.addPropertyValue("Family", "Given", "Middle", "Prefix", "Suffix");
        entry.addProperty(property);
        entry.consolidateFields();

        assertEquals("Family", entry.getFamilyName());
        assertEquals("Given", entry.getGivenName());
        assertEquals("Middle", entry.getMiddleName());
        assertEquals("Prefix", entry.getPrefix());
        assertEquals("Suffix", entry.getSuffix());

        ContentResolver resolver = getContext().getContentResolver();
        ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        entry.constructInsertOperations(resolver, operationList);

        // Need too many details for testing these. Just check basics.
        // TODO: introduce nice-to-test mechanism here.
        assertEquals(2, operationList.size());
        assertEquals(ContentProviderOperation.TYPE_INSERT, operationList.get(0).getType());
        assertEquals(ContentProviderOperation.TYPE_INSERT, operationList.get(1).getType());
    }

    /**
     * Tests that VCardEntry refrains from emitting unnecessary insert operation.
     */
    public void testConstructInsertOperationsEmptyData() {
        VCardEntry entry = new VCardEntry();
        ContentResolver resolver = getContext().getContentResolver();
        ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        entry.constructInsertOperations(resolver, operationList);
        assertEquals(0, operationList.size());
    }

    // TODO: add bunch of test for constructInsertOperations..
}