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
import com.android.vcard.exception.VCardException;

import android.test.AndroidTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VCardInterpreterTests extends AndroidTestCase {
    private enum Order {
        START,
        END,
        START_ENTRY,
        END_ENTRY,
        START_PROPERTY,
        END_PROPERTY,
        PROPERTY_GROUP,
        PROPERTY_NAME,
        PROPERTY_PARAM_TYPE,
        PROPERTY_PARAM_VALUE,
        PROPERTY_VALUES
    }

    private class MockVCardInterpreter implements VCardInterpreter {
        private final List<String> mHistory = new ArrayList<String>();
        private final List<Object> mExpectedOrder = new ArrayList<Object>();

        public MockVCardInterpreter addExpectedOrder(Order order) {
            mExpectedOrder.add(order);
            return this;
        }

        public MockVCardInterpreter addExpectedOrderGroup(Order... orders) {
            mExpectedOrder.add(new HashSet<Order>(Arrays.asList(orders)));
            return this;
        }

        private void inspectOrder(Order order) {
            mHistory.add(order.toString());
            final Object top = mExpectedOrder.get(0);
            if (top instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<Order> orderSet = (Set<Order>)top;
                assertTrue(String.format("Unexpected order: %s",
                        Arrays.toString(mHistory.toArray(new String[0]))),
                        orderSet.remove(order));
                if (orderSet.isEmpty()) {
                    mExpectedOrder.remove(0);
                }
            } else {
                assertEquals(top, order);
                mExpectedOrder.remove(0);
            }
        }

        public void verify() {
            assertTrue(String.format("Remaining: " + Arrays.toString(mExpectedOrder.toArray())),
                    mExpectedOrder.isEmpty());
        }

        @Override
        public void start() {
            inspectOrder(Order.START);
        }

        @Override
        public void end() {
            inspectOrder(Order.END);
        }

        @Override
        public void startEntry() {
            inspectOrder(Order.START_ENTRY);
        }

        @Override
        public void endEntry() {
            inspectOrder(Order.END_ENTRY);
        }

        @Override
        public void startProperty() {
            inspectOrder(Order.START_PROPERTY);
        }

        @Override
        public void endProperty() {
            inspectOrder(Order.END_PROPERTY);
        }

        @Override
        public void propertyGroup(String group) {
            inspectOrder(Order.PROPERTY_GROUP);
        }

        @Override
        public void propertyName(String name) {
            inspectOrder(Order.PROPERTY_NAME);
        }

        @Override
        public void propertyParamType(String type) {
            inspectOrder(Order.PROPERTY_PARAM_TYPE);
        }

        @Override
        public void propertyParamValue(String value) {
            inspectOrder(Order.PROPERTY_PARAM_VALUE);
        }

        @Override
        public void propertyValues(List<String> values) {
            inspectOrder(Order.PROPERTY_VALUES);
        }
    }

    public void testSimple() throws IOException, VCardException {
        InputStream inputStream = getContext().getResources().openRawResource(R.raw.v21_simple_1);
        VCardParser parser = new VCardParser_V21();
        MockVCardInterpreter interpreter = new MockVCardInterpreter();
        interpreter.addExpectedOrder(Order.START)
                .addExpectedOrder(Order.START_ENTRY)
                .addExpectedOrder(Order.START_PROPERTY)
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.END_ENTRY)
                .addExpectedOrder(Order.END);
        parser.parse(inputStream, interpreter);
        interpreter.verify();
    }

    public void testNest() throws IOException, VCardException {
        InputStream inputStream = getContext().getResources().openRawResource(R.raw.v21_nest);
        VCardParser parser = new VCardParser_V21();
        MockVCardInterpreter interpreter = new MockVCardInterpreter();
        interpreter.addExpectedOrder(Order.START)
                .addExpectedOrder(Order.START_ENTRY)
                .addExpectedOrder(Order.START_PROPERTY)  // For VERSION
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.START_PROPERTY)  // For N
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.START_ENTRY)  // First nested vCard begins
                .addExpectedOrder(Order.START_PROPERTY)  // For VERSION
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.START_PROPERTY)  // For N
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.END_ENTRY)  // First nested vCard ends
                .addExpectedOrder(Order.START_ENTRY)  // Second nested vCard begins
                .addExpectedOrder(Order.START_PROPERTY)  // For VERSION
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.START_PROPERTY)  // For N
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.END_ENTRY)  // Second nested vCard ends
                .addExpectedOrder(Order.START_PROPERTY)  // For TEL
                .addExpectedOrderGroup(Order.PROPERTY_NAME, Order.PROPERTY_VALUES)
                .addExpectedOrder(Order.END_PROPERTY)
                .addExpectedOrder(Order.END_ENTRY)
                .addExpectedOrder(Order.END);
        parser.parse(inputStream, interpreter);
        interpreter.verify();
    }
}