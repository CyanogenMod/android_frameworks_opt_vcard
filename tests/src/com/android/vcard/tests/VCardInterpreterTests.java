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

import com.android.vcard.VCardInterpreter;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardProperty;
import com.android.vcard.exception.VCardException;

import android.test.AndroidTestCase;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VCardInterpreterTests extends AndroidTestCase {
    private enum Order {
        START,
        END,
        START_ENTRY,
        END_ENTRY,
        PROPERTY_CREATED,
    }

    private class MockVCardInterpreter implements VCardInterpreter {
        private final List<Order> mHistory = new ArrayList<Order>();
        private final List<Order> mExpectedOrder = new ArrayList<Order>();

        public MockVCardInterpreter addExpectedOrder(Order order) {
            mExpectedOrder.add(order);
            return this;
        }

        private void inspectOrder(Order order) {
            mHistory.add(order);
            final Order top = mExpectedOrder.get(0);
            assertEquals(top, order);
            mExpectedOrder.remove(0);
        }

        public void verify() {
            assertTrue(String.format("Remaining: " + Arrays.toString(mExpectedOrder.toArray())),
                    mExpectedOrder.isEmpty());
        }

        @Override
        public void onVCardStarted() {
            inspectOrder(Order.START);
        }

        @Override
        public void onVCardEnded() {
            inspectOrder(Order.END);
        }

        @Override
        public void onEntryStarted() {
            inspectOrder(Order.START_ENTRY);
        }

        @Override
        public void onEntryEnded() {
            inspectOrder(Order.END_ENTRY);
        }

        @Override
        public void onPropertyCreated(VCardProperty property) {
            inspectOrder(Order.PROPERTY_CREATED);
        }
    }

    public void testSimple() throws IOException, VCardException {
        InputStream inputStream = getContext().getResources().openRawResource(R.raw.v21_simple_1);
        VCardParser parser = new VCardParser_V21();
        MockVCardInterpreter interpreter = new MockVCardInterpreter();
        interpreter.addExpectedOrder(Order.START)
                .addExpectedOrder(Order.START_ENTRY)
                .addExpectedOrder(Order.PROPERTY_CREATED)
                .addExpectedOrder(Order.END_ENTRY)
                .addExpectedOrder(Order.END);
        parser.addInterpreter(interpreter);
        parser.parse(inputStream);
        interpreter.verify();
    }

    public void testNest() throws IOException, VCardException {
        InputStream inputStream = getContext().getResources().openRawResource(R.raw.v21_nest);
        VCardParser parser = new VCardParser_V21();
        MockVCardInterpreter interpreter = new MockVCardInterpreter();
        interpreter.addExpectedOrder(Order.START)
                .addExpectedOrder(Order.START_ENTRY)
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For VERSION
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For N
                .addExpectedOrder(Order.START_ENTRY)  // First nested vCard begins
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For VERSION
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For N
                .addExpectedOrder(Order.END_ENTRY)  // First nested vCard ends
                .addExpectedOrder(Order.START_ENTRY)  // Second nested vCard begins
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For VERSION
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For N
                .addExpectedOrder(Order.END_ENTRY)  // Second nested vCard ends
                .addExpectedOrder(Order.PROPERTY_CREATED)  // For TEL
                .addExpectedOrder(Order.END_ENTRY)
                .addExpectedOrder(Order.END);
        parser.addInterpreter(interpreter);
        parser.parse(inputStream);
        interpreter.verify();
    }
}