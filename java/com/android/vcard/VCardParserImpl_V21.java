/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.vcard.exception.VCardAgentNotSupportedException;
import com.android.vcard.exception.VCardException;
import com.android.vcard.exception.VCardInvalidCommentLineException;
import com.android.vcard.exception.VCardInvalidLineException;
import com.android.vcard.exception.VCardVersionException;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * Basic implementation achieving vCard parsing. Based on vCard 2.1.
 * </p>
 * @hide
 */
/* package */ class VCardParserImpl_V21 {
    private static final String LOG_TAG = VCardConstants.LOG_TAG;

    private static final class EmptyInterpreter implements VCardInterpreter {
        @Override
        public void end() {
        }
        @Override
        public void endEntry() {
        }
        @Override
        public void endProperty() {
        }
        @Override
        public void propertyGroup(String group) {
        }
        @Override
        public void propertyName(String name) {
        }
        @Override
        public void propertyParamType(String type) {
        }
        @Override
        public void propertyParamValue(String value) {
        }
        @Override
        public void propertyValues(List<String> values) {
        }
        @Override
        public void start() {
        }
        @Override
        public void startEntry() {
        }
        @Override
        public void startProperty() {
        }
    }

    protected static final class CustomBufferedReader extends BufferedReader {
        private long mTime;

        /**
         * Needed since "next line" may be null due to end of line.
         */
        private boolean mNextLineIsValid;
        private String mNextLine;

        public CustomBufferedReader(Reader in) {
            super(in);
        }

        @Override
        public String readLine() throws IOException {
            if (mNextLineIsValid) {
                final String ret = mNextLine;
                mNextLine = null;
                mNextLineIsValid = false;
                return ret;
            }

            final long start = System.currentTimeMillis();
            final String line = super.readLine();
            final long end = System.currentTimeMillis();
            mTime += end - start;
            return line;
        }

        /**
         * Read one line, but make this object store it in its queue.
         */
        public String peekLine() throws IOException {
            if (!mNextLineIsValid) {
                final long start = System.currentTimeMillis();
                final String line = super.readLine();
                final long end = System.currentTimeMillis();
                mTime += end - start;

                mNextLine = line;
                mNextLineIsValid = true;
            }

            return mNextLine;
        }

        public long getTotalmillisecond() {
            return mTime;
        }
    }

    /**
     * Stores param information for a property. Used with {@link PropertyData}.
     *
     * e.g. "LANGUAGE=jp" -> paramName = "LANGUAGE", paramvalue = "jp"
     */
    private class ParamPair {
        public String paramName;
        public String paramValue;
        public ParamPair(String paramName, String paramValue) {
            this.paramName = paramName;
            this.paramValue = paramValue;
        }
        @Override
        public String toString() {
            return paramName + ", " + paramValue;
        }
    }

    /**
     * Intermediate data for storing property.
     *
     * Name, group and param are property encoded when this object is prepared.
     * Value isn't encoded yet at this point.
     */
    protected class PropertyData {
        private String mName;
        private List<String> mGroupList;
        private List<ParamPair> mParamList;
        private String mRawValue;

        public void setName(String name) throws VCardException {
            if (mName != null) {
                throw new VCardException(
                        String.format("Property name is re-defined " +
                                "(existing: %s, requested: %s", mName, name));
            }
            mName = name;
        }

        public void addGroup(String group) {
            if (mGroupList == null) {
                mGroupList = new ArrayList<String>();
            }
            mGroupList.add(group);
        }

        public void addParam(String paramName, String paramValue) {
            if (mParamList == null) {
                mParamList = new ArrayList<ParamPair>();
            }
            mParamList.add(new ParamPair(paramName, paramValue));
        }

        public void setRawValue(String rawValue) throws VCardException {
            if (mRawValue != null) {
                throw new VCardException(
                        String.format("Property value is re-defined " +
                                "(existing: %s, requested: %s", mRawValue, rawValue));
            }
            mRawValue = rawValue;
        }

        public String getName() {
            return mName;
        }

        public List<String> getGroupList() {
            return mGroupList;
        }

        public List<ParamPair> getParamList() {
            return mParamList;
        }

        public String getRawValue() {
            return mRawValue;
        }
     }

    private static final String DEFAULT_ENCODING = "8BIT";

    // TODO: remove this.
    protected boolean mCanceled;
    protected final String mIntermediateCharset;

    private VCardInterpreter mInterpreter;

    /**
     * <p>
     * The encoding type for deconding byte streams. This member variable is
     * reset to a default encoding every time when a new item comes.
     * </p>
     * <p>
     * "Encoding" in vCard is different from "Charset". It is mainly used for
     * addresses, notes, images. "7BIT", "8BIT", "BASE64", and
     * "QUOTED-PRINTABLE" are known examples.
     * </p>
     */
    protected String mCurrentEncoding;

    /**
     * <p>
     * The reader object to be used internally.
     * </p>
     * <p>
     * Developers should not directly read a line from this object. Use
     * getLine() unless there some reason.
     * </p>
     */
    protected CustomBufferedReader mReader;

    /**
     * <p>
     * Set for storing unkonwn TYPE attributes, which is not acceptable in vCard
     * specification, but happens to be seen in real world vCard.
     * </p>
     * <p>
     * We just accept those invalid types after emitting a warning for each of it.
     * </p>
     */
    protected final Set<String> mUnknownTypeSet = new HashSet<String>();

    /**
     * <p>
     * Set for storing unkonwn VALUE attributes, which is not acceptable in
     * vCard specification, but happens to be seen in real world vCard.
     * </p>
     * <p>
     * We just accept those invalid types after emitting a warning for each of it.
     * </p>
     */
    protected final Set<String> mUnknownValueSet = new HashSet<String>();


    public VCardParserImpl_V21() {
        this(VCardConfig.VCARD_TYPE_DEFAULT);
    }

    public VCardParserImpl_V21(int vcardType) {
        mIntermediateCharset =  VCardConfig.DEFAULT_INTERMEDIATE_CHARSET;
    }

    /**
     * <p>
     * Parses the file at the given position.
     * </p>
     */
    // <pre class="prettyprint">vcard_file = [wsls] vcard [wsls]</pre>
    protected void parseVCardFile() throws IOException, VCardException {
        while (true) {
            if (mCanceled) {
                Log.i(LOG_TAG, "Cancel request has come. exitting parse operation.");
                break;
            }
            if (!parseOneVCard()) {
                break;
            }
        }
    }

    /**
     * @return true when a given property name is a valid property name.
     */
    protected boolean isValidPropertyName(final String propertyName) {
        if (!(getKnownPropertyNameSet().contains(propertyName.toUpperCase()) ||
                propertyName.startsWith("X-"))
                && !mUnknownTypeSet.contains(propertyName)) {
            mUnknownTypeSet.add(propertyName);
            Log.w(LOG_TAG, "Property name unsupported by vCard 2.1: " + propertyName);
        }
        return true;
    }

    /**
     * @return String. It may be null, or its length may be 0
     * @throws IOException
     */
    protected String getLine() throws IOException {
        return mReader.readLine();
    }

    protected String peekLine() throws IOException {
        return mReader.peekLine();
    }

    /**
     * @return String with it's length > 0
     * @throws IOException
     * @throws VCardException when the stream reached end of line
     */
    protected String getNonEmptyLine() throws IOException, VCardException {
        String line;
        while (true) {
            line = getLine();
            if (line == null) {
                throw new VCardException("Reached end of buffer.");
            } else if (line.trim().length() > 0) {
                return line;
            }
        }
    }

    /**
     * <code>
     * vcard = "BEGIN" [ws] ":" [ws] "VCARD" [ws] 1*CRLF
     *         items *CRLF
     *         "END" [ws] ":" [ws] "VCARD"
     * </code>
     */
    private boolean parseOneVCard() throws IOException, VCardException {
        // reset for this entire vCard.
        mCurrentEncoding = DEFAULT_ENCODING;

        boolean allowGarbage = false;
        if (!readBeginVCard(allowGarbage)) {
            return false;
        }
        mInterpreter.startEntry();
        parseItems();
        mInterpreter.endEntry();
        return true;
    }

    /**
     * @return True when successful. False when reaching the end of line
     * @throws IOException
     * @throws VCardException
     */
    protected boolean readBeginVCard(boolean allowGarbage) throws IOException, VCardException {
        // TODO: use consructPropertyLine().
        String line;
        do {
            while (true) {
                line = getLine();
                if (line == null) {
                    return false;
                } else if (line.trim().length() > 0) {
                    break;
                }
            }
            final String[] strArray = line.split(":", 2);
            final int length = strArray.length;

            // Although vCard 2.1/3.0 specification does not allow lower cases,
            // we found vCard file emitted by some external vCard expoter have such
            // invalid Strings.
            // e.g. BEGIN:vCard
            if (length == 2 && strArray[0].trim().equalsIgnoreCase("BEGIN")
                    && strArray[1].trim().equalsIgnoreCase("VCARD")) {
                return true;
            } else if (!allowGarbage) {
                throw new VCardException("Expected String \"BEGIN:VCARD\" did not come "
                        + "(Instead, \"" + line + "\" came)");
            }
        } while (allowGarbage);

        throw new VCardException("Reached where must not be reached.");
    }

    /**
     * Parses lines other than the first "BEGIN:VCARD". Takes care of "END:VCARD"n and
     * "BEGIN:VCARD" in nested vCard.
     */
    /*
     * items = *CRLF item / item
     *
     * Note: BEGIN/END aren't include in the original spec while this method handles them.
     */
    protected void parseItems() throws IOException, VCardException {
        boolean ended = false;

        try {
            ended = parseItem();
        } catch (VCardInvalidCommentLineException e) {
            Log.e(LOG_TAG, "Invalid line which looks like some comment was found. Ignored.");
        }

        while (!ended) {
            try {
                ended = parseItem();
            } catch (VCardInvalidCommentLineException e) {
                Log.e(LOG_TAG, "Invalid line which looks like some comment was found. Ignored.");
            }
        }
    }

    /*
     * item = [groups "."] name [params] ":" value CRLF / [groups "."] "ADR"
     * [params] ":" addressparts CRLF / [groups "."] "ORG" [params] ":" orgparts
     * CRLF / [groups "."] "N" [params] ":" nameparts CRLF / [groups "."]
     * "AGENT" [params] ":" vcard CRLF
     */
    protected boolean parseItem() throws IOException, VCardException {
        // Reset for an item.
        mCurrentEncoding = DEFAULT_ENCODING;

        final String line = getNonEmptyLine();
        final PropertyData propertyData = constructPropertyData(line);

        final String propertyNameUpper = propertyData.getName().toUpperCase();
        final String propertyValue = propertyData.getRawValue();

        if (propertyNameUpper.equals(VCardConstants.PROPERTY_BEGIN)) {
            if (propertyValue.equalsIgnoreCase("VCARD")) {
                handleNest();
            } else {
                throw new VCardException("Unknown BEGIN type: " + propertyValue);
            }
        } else if (propertyNameUpper.equals(VCardConstants.PROPERTY_END)) {
            if (propertyValue.equalsIgnoreCase("VCARD")) {
                return true;  // Ended.
            } else {
                throw new VCardException("Unknown END type: " + propertyValue);
            }
        } else {
            mInterpreter.startProperty();
            sendPropertyLineMetaInfo(propertyData);
            parseItemInter(propertyNameUpper, propertyValue);
            mInterpreter.endProperty();
        }
        return false;
    }

    private void sendPropertyLineMetaInfo(PropertyData propertyData) {
        final List<String> groupList = propertyData.getGroupList();
        if (groupList != null) {
            for (String group : groupList) {
                mInterpreter.propertyGroup(group);
            }
        }
        mInterpreter.propertyName(propertyData.getName());

        final List<ParamPair> paramList = propertyData.getParamList();
        if (paramList != null) {
            for (ParamPair pair : paramList) {
                mInterpreter.propertyParamType(pair.paramName);
                mInterpreter.propertyParamValue(pair.paramValue);
            }
        }
    }

    private void parseItemInter(String propertyNameUpper, String propertyValue)
            throws IOException, VCardException {
        if (propertyNameUpper.equals(VCardConstants.PROPERTY_ADR)
                || propertyNameUpper.equals(VCardConstants.PROPERTY_ORG)
                || propertyNameUpper.equals(VCardConstants.PROPERTY_N)) {
            handleMultiplePropertyValue(propertyNameUpper, propertyValue);
        } else if (propertyNameUpper.equals(VCardConstants.PROPERTY_AGENT)) {
            handleAgent(propertyValue);
        } else if (isValidPropertyName(propertyNameUpper)) {
            if (propertyNameUpper.equals(VCardConstants.PROPERTY_VERSION) &&
                    !propertyValue.equals(getVersionString())) {
                throw new VCardVersionException("Incompatible version: " + propertyValue + " != "
                        + getVersionString());
            }
            handlePropertyValue(propertyNameUpper, propertyValue);
        } else {
            throw new VCardException("Unknown property name: \"" + propertyNameUpper + "\"");
        }
    }

    private void handleNest() throws IOException, VCardException {
        mInterpreter.startEntry();
        parseItems();
        mInterpreter.endEntry();
    }

    // For performance reason, the states for group and property name are merged into one.
    static private final int STATE_GROUP_OR_PROPERTY_NAME = 0;
    static private final int STATE_PARAMS = 1;
    // vCard 3.0 specification allows double-quoted parameters, while vCard 2.1 does not.
    static private final int STATE_PARAMS_IN_DQUOTE = 2;

    protected PropertyData constructPropertyData(String line) throws VCardException {
        final PropertyData propertyData = new PropertyData();

        final int length = line.length();
        if (length > 0 && line.charAt(0) == '#') {
            throw new VCardInvalidCommentLineException();
        }

        int state = STATE_GROUP_OR_PROPERTY_NAME;
        int nameIndex = 0;

        // This loop is developed so that we don't have to take care of bottle neck here.
        // Refactor carefully when you need to do so.
        for (int i = 0; i < length; i++) {
            final char ch = line.charAt(i);
            switch (state) {
                case STATE_GROUP_OR_PROPERTY_NAME: {
                    if (ch == ':') {  // End of a property name.
                        final String propertyName = line.substring(nameIndex, i);
                        propertyData.setName(propertyName);
                        propertyData.setRawValue( i < length - 1 ? line.substring(i + 1) : "");
                        return propertyData;
                    } else if (ch == '.') {  // Each group is followed by the dot.
                        final String groupName = line.substring(nameIndex, i);
                        if (groupName.length() == 0) {
                            Log.w(LOG_TAG, "Empty group found. Ignoring.");
                        } else {
                            propertyData.addGroup(groupName);
                        }
                        nameIndex = i + 1;  // Next should be another group or a property name.
                    } else if (ch == ';') {  // End of property name and beginneng of parameters.
                        final String propertyName = line.substring(nameIndex, i);
                        propertyData.setName(propertyName);
                        nameIndex = i + 1;
                        state = STATE_PARAMS;  // Start parameter parsing.
                    }
                    // TODO: comma support (in vCard 3.0 and 4.0).
                    break;
                }
                case STATE_PARAMS: {
                    if (ch == '"') {
                        if (VCardConstants.VERSION_V21.equalsIgnoreCase(getVersionString())) {
                            Log.w(LOG_TAG, "Double-quoted params found in vCard 2.1. " +
                                    "Silently allow it");
                        }
                        state = STATE_PARAMS_IN_DQUOTE;
                    } else if (ch == ';') {  // Starts another param.
                        handleParams(propertyData, line.substring(nameIndex, i));
                        nameIndex = i + 1;
                    } else if (ch == ':') {  // End of param and beginenning of values.
                        handleParams(propertyData, line.substring(nameIndex, i));
                        propertyData.setRawValue(i < length - 1 ? line.substring(i + 1) : "");
                        return propertyData;
                    }
                    break;
                }
                case STATE_PARAMS_IN_DQUOTE: {
                    if (ch == '"') {
                        if (VCardConstants.VERSION_V21.equalsIgnoreCase(getVersionString())) {
                            Log.w(LOG_TAG, "Double-quoted params found in vCard 2.1. " +
                                    "Silently allow it");
                        }
                        state = STATE_PARAMS;
                    }
                    break;
                }
            }
        }

        throw new VCardInvalidLineException("Invalid line: \"" + line + "\"");
    }

    /*
     * params = ";" [ws] paramlist paramlist = paramlist [ws] ";" [ws] param /
     * param param = "TYPE" [ws] "=" [ws] ptypeval / "VALUE" [ws] "=" [ws]
     * pvalueval / "ENCODING" [ws] "=" [ws] pencodingval / "CHARSET" [ws] "="
     * [ws] charsetval / "LANGUAGE" [ws] "=" [ws] langval / "X-" word [ws] "="
     * [ws] word / knowntype
     */
    protected void handleParams(PropertyData propertyData, String params)
            throws VCardException {
        final String[] strArray = params.split("=", 2);
        if (strArray.length == 2) {
            final String paramName = strArray[0].trim().toUpperCase();
            String paramValue = strArray[1].trim();
            if (paramName.equals("TYPE")) {
                handleType(propertyData, paramValue);
            } else if (paramName.equals("VALUE")) {
                handleValue(propertyData, paramValue);
            } else if (paramName.equals("ENCODING")) {
                handleEncoding(propertyData, paramValue);
            } else if (paramName.equals("CHARSET")) {
                handleCharset(propertyData, paramValue);
            } else if (paramName.equals("LANGUAGE")) {
                handleLanguage(propertyData, paramValue);
            } else if (paramName.startsWith("X-")) {
                handleAnyParam(propertyData, paramName, paramValue);
            } else {
                throw new VCardException("Unknown type \"" + paramName + "\"");
            }
        } else {
            handleParamWithoutName(propertyData, strArray[0]);
        }
    }

    /**
     * vCard 3.0 parser implementation may throw VCardException.
     */
    protected void handleParamWithoutName(PropertyData propertyData, final String paramValue) {
        handleType(propertyData, paramValue);
    }

    /*
     * ptypeval = knowntype / "X-" word
     */
    protected void handleType(PropertyData propertyData, final String ptypeval) {
        if (!(getKnownTypeSet().contains(ptypeval.toUpperCase())
                || ptypeval.startsWith("X-"))
                && !mUnknownTypeSet.contains(ptypeval)) {
            mUnknownTypeSet.add(ptypeval);
            Log.w(LOG_TAG, String.format("TYPE unsupported by %s: ", getVersion(), ptypeval));
        }
        propertyData.addParam(VCardConstants.PARAM_TYPE, ptypeval);
    }

    /*
     * pvalueval = "INLINE" / "URL" / "CONTENT-ID" / "CID" / "X-" word
     */
    protected void handleValue(PropertyData propertyData, final String pvalueval) {
        if (!(getKnownValueSet().contains(pvalueval.toUpperCase())
                || pvalueval.startsWith("X-")
                || mUnknownValueSet.contains(pvalueval))) {
            mUnknownValueSet.add(pvalueval);
            Log.w(LOG_TAG, String.format(
                    "The value unsupported by TYPE of %s: ", getVersion(), pvalueval));
        }
        propertyData.addParam(VCardConstants.PARAM_VALUE, pvalueval);
    }

    /*
     * pencodingval = "7BIT" / "8BIT" / "QUOTED-PRINTABLE" / "BASE64" / "X-" word
     */
    protected void handleEncoding(PropertyData propertyData, String pencodingval)
            throws VCardException {
        if (getAvailableEncodingSet().contains(pencodingval) ||
                pencodingval.startsWith("X-")) {
            propertyData.addParam(VCardConstants.PARAM_ENCODING, pencodingval);
            // Update encoding right away, as this is needed to understanding other params.
            mCurrentEncoding = pencodingval;
        } else {
            throw new VCardException("Unknown encoding \"" + pencodingval + "\"");
        }
    }

    /**
     * <p>
     * vCard 2.1 specification only allows us-ascii and iso-8859-xxx (See RFC 1521),
     * but recent vCard files often contain other charset like UTF-8, SHIFT_JIS, etc.
     * We allow any charset.
     * </p>
     */
    protected void handleCharset(PropertyData propertyData, String charsetval) {
        propertyData.addParam(VCardConstants.PARAM_CHARSET, charsetval);
    }

    /**
     * See also Section 7.1 of RFC 1521
     */
    protected void handleLanguage(PropertyData propertyData, String langval)
            throws VCardException {
        String[] strArray = langval.split("-");
        if (strArray.length != 2) {
            throw new VCardException("Invalid Language: \"" + langval + "\"");
        }
        String tmp = strArray[0];
        int length = tmp.length();
        for (int i = 0; i < length; i++) {
            if (!isAsciiLetter(tmp.charAt(i))) {
                throw new VCardException("Invalid Language: \"" + langval + "\"");
            }
        }
        tmp = strArray[1];
        length = tmp.length();
        for (int i = 0; i < length; i++) {
            if (!isAsciiLetter(tmp.charAt(i))) {
                throw new VCardException("Invalid Language: \"" + langval + "\"");
            }
        }
        propertyData.addParam(VCardConstants.PARAM_LANGUAGE, langval);
    }

    private boolean isAsciiLetter(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
            return true;
        }
        return false;
    }

    /**
     * Mainly for "X-" type. This accepts any kind of type without check.
     */
    protected void handleAnyParam(
            PropertyData propertyData, String paramName, String paramValue) {
        propertyData.addParam(paramName, paramValue);
    }

    protected void handlePropertyValue(String propertyName, String propertyValue)
            throws IOException, VCardException {
        final String upperEncoding = mCurrentEncoding.toUpperCase();
        if (upperEncoding.equals(VCardConstants.PARAM_ENCODING_QP)) {
            final String result = getQuotedPrintable(propertyValue);
            final ArrayList<String> v = new ArrayList<String>();
            v.add(result);
            mInterpreter.propertyValues(v);
        } else if (upperEncoding.equals(VCardConstants.PARAM_ENCODING_BASE64)
                || upperEncoding.equals(VCardConstants.PARAM_ENCODING_B)) {
            // It is very rare, but some BASE64 data may be so big that
            // OutOfMemoryError occurs. To ignore such cases, use try-catch.
            try {
                final ArrayList<String> arrayList = new ArrayList<String>();
                arrayList.add(getBase64(propertyValue));
                mInterpreter.propertyValues(arrayList);
            } catch (OutOfMemoryError error) {
                Log.e(LOG_TAG, "OutOfMemoryError happened during parsing BASE64 data!");
                mInterpreter.propertyValues(null);
            }
        } else {
            if (!(upperEncoding.equals("7BIT") || upperEncoding.equals("8BIT") ||
                    upperEncoding.startsWith("X-"))) {
                Log.w(LOG_TAG,
                        String.format("The encoding \"%s\" is unsupported by vCard %s",
                                mCurrentEncoding, getVersionString()));
            }

            // Some device uses line folding defined in RFC 2425, which is not allowed
            // in vCard 2.1 (while needed in vCard 3.0).
            //
            // e.g.
            // BEGIN:VCARD
            // VERSION:2.1
            // N:;Omega;;;
            // EMAIL;INTERNET:"Omega"
            //   <omega@example.com>
            // FN:Omega
            // END:VCARD
            //
            // The vCard above assumes that email address should become:
            // "Omega" <omega@example.com>
            //
            // But vCard 2.1 requires Quote-Printable when a line contains line break(s).
            //
            // For more information about line folding,
            // see "5.8.1. Line delimiting and folding" in RFC 2425.
            //
            // We take care of this case more formally in vCard 3.0, so we only need to
            // do this in vCard 2.1.
            if (getVersion() == VCardConfig.VERSION_21) {
                StringBuilder builder = null;
                while (true) {
                    final String nextLine = peekLine();
                    // We don't need to care too much about this exceptional case,
                    // but we should not wrongly eat up "END:VCARD", since it critically
                    // breaks this parser's state machine.
                    // Thus we roughly look over the next line and confirm it is at least not
                    // "END:VCARD". This extra fee is worth paying. This is exceptional
                    // anyway.
                    if (!TextUtils.isEmpty(nextLine) &&
                            nextLine.charAt(0) == ' ' &&
                            !"END:VCARD".contains(nextLine.toUpperCase())) {
                        getLine();  // Drop the next line.

                        if (builder == null) {
                            builder = new StringBuilder();
                            builder.append(propertyValue);
                        }
                        builder.append(nextLine.substring(1));
                    } else {
                        break;
                    }
                }
                if (builder != null) {
                    propertyValue = builder.toString();
                }
            }

            ArrayList<String> v = new ArrayList<String>();
            v.add(maybeUnescapeText(propertyValue));
            mInterpreter.propertyValues(v);
        }
    }

    /**
     * <p>
     * Parses and returns Quoted-Printable.
     * </p>
     *
     * @param firstString The string following a parameter name and attributes.
     *            Example: "string" in
     *            "ADR:ENCODING=QUOTED-PRINTABLE:string\n\r".
     * @return whole Quoted-Printable string, including a given argument and
     *         following lines. Excludes the last empty line following to Quoted
     *         Printable lines.
     * @throws IOException
     * @throws VCardException
     */
    private String getQuotedPrintable(String firstString) throws IOException, VCardException {
        // Specifically, there may be some padding between = and CRLF.
        // See the following:
        //
        // qp-line := *(qp-segment transport-padding CRLF)
        // qp-part transport-padding
        // qp-segment := qp-section *(SPACE / TAB) "="
        // ; Maximum length of 76 characters
        //
        // e.g. (from RFC 2045)
        // Now's the time =
        // for all folk to come=
        // to the aid of their country.
        if (firstString.trim().endsWith("=")) {
            // remove "transport-padding"
            int pos = firstString.length() - 1;
            while (firstString.charAt(pos) != '=') {
            }
            StringBuilder builder = new StringBuilder();
            builder.append(firstString.substring(0, pos + 1));
            builder.append("\r\n");
            String line;
            while (true) {
                line = getLine();
                if (line == null) {
                    throw new VCardException("File ended during parsing a Quoted-Printable String");
                }
                if (line.trim().endsWith("=")) {
                    // remove "transport-padding"
                    pos = line.length() - 1;
                    while (line.charAt(pos) != '=') {
                    }
                    builder.append(line.substring(0, pos + 1));
                    builder.append("\r\n");
                } else {
                    builder.append(line);
                    break;
                }
            }
            return builder.toString();
        } else {
            return firstString;
        }
    }

    protected String getBase64(String firstString) throws IOException, VCardException {
        final StringBuilder builder = new StringBuilder();
        builder.append(firstString);

        while (true) {
            final String line = peekLine();
            if (line == null) {
                throw new VCardException("File ended during parsing BASE64 binary");
            }

            // vCard 2.1 requires two spaces at the end of BASE64 strings, but some vCard doesn't
            // have them. We try to detect those cases using semi-colon, given BASE64 doesn't
            // contain it. Specifically BASE64 doesn't have semi-colon in it, so we should be able
            // to detect the case safely.
            if (line.contains(":")) {
                if (getKnownPropertyNameSet().contains(
                        line.substring(0, line.indexOf(":")).toUpperCase())) {
                    Log.w(LOG_TAG, "Found a next property during parsing a BASE64 string, " +
                            "which must not contain semi-colon. Treat the line as next property.");
                    Log.w(LOG_TAG, "Problematic line: " + line.trim());
                    break;
                }
            }

            // Consume the line.
            getLine();

            if (line.length() == 0) {
                break;
            }
            builder.append(line);
        }

        return builder.toString();
    }

    /**
     * <p>
     * Mainly for "ADR", "ORG", and "N"
     * </p>
     */
    /*
     * addressparts = 0*6(strnosemi ";") strnosemi ; PO Box, Extended Addr,
     * Street, Locality, Region, Postal Code, Country Name orgparts =
     * *(strnosemi ";") strnosemi ; First is Organization Name, remainder are
     * Organization Units. nameparts = 0*4(strnosemi ";") strnosemi ; Family,
     * Given, Middle, Prefix, Suffix. ; Example:Public;John;Q.;Reverend Dr.;III,
     * Esq. strnosemi = *(*nonsemi ("\;" / "\" CRLF)) *nonsemi ; To include a
     * semicolon in this string, it must be escaped ; with a "\" character. We
     * do not care the number of "strnosemi" here. We are not sure whether we
     * should add "\" CRLF to each value. We exclude them for now.
     */
    protected void handleMultiplePropertyValue(String propertyName, String propertyValue)
            throws IOException, VCardException {
        // vCard 2.1 does not allow QUOTED-PRINTABLE here, but some
        // softwares/devices
        // emit such data.
        if (mCurrentEncoding.equalsIgnoreCase("QUOTED-PRINTABLE")) {
            propertyValue = getQuotedPrintable(propertyValue);
        }

        mInterpreter.propertyValues(VCardUtils.constructListFromValue(propertyValue,
                getVersion()));
    }

    /*
     * vCard 2.1 specifies AGENT allows one vcard entry. Currently we emit an
     * error toward the AGENT property.
     * // TODO: Support AGENT property.
     * item =
     * ... / [groups "."] "AGENT" [params] ":" vcard CRLF vcard = "BEGIN" [ws]
     * ":" [ws] "VCARD" [ws] 1*CRLF items *CRLF "END" [ws] ":" [ws] "VCARD"
     */
    protected void handleAgent(final String propertyValue) throws VCardException {
        if (!propertyValue.toUpperCase().contains("BEGIN:VCARD")) {
            // Apparently invalid line seen in Windows Mobile 6.5. Ignore them.
            return;
        } else {
            throw new VCardAgentNotSupportedException("AGENT Property is not supported now.");
        }
    }

    /**
     * For vCard 3.0.
     */
    protected String maybeUnescapeText(final String text) {
        return text;
    }

    /**
     * Returns unescaped String if the character should be unescaped. Return
     * null otherwise. e.g. In vCard 2.1, "\;" should be unescaped into ";"
     * while "\x" should not be.
     */
    protected String maybeUnescapeCharacter(final char ch) {
        return unescapeCharacter(ch);
    }

    /* package */ static String unescapeCharacter(final char ch) {
        // Original vCard 2.1 specification does not allow transformation
        // "\:" -> ":", "\," -> ",", and "\\" -> "\", but previous
        // implementation of
        // this class allowed them, so keep it as is.
        if (ch == '\\' || ch == ';' || ch == ':' || ch == ',') {
            return String.valueOf(ch);
        } else {
            return null;
        }
    }

    /**
     * @return {@link VCardConfig#VERSION_21}
     */
    protected int getVersion() {
        return VCardConfig.VERSION_21;
    }

    /**
     * @return {@link VCardConfig#VERSION_30}
     */
    protected String getVersionString() {
        return VCardConstants.VERSION_V21;
    }

    protected Set<String> getKnownPropertyNameSet() {
        return VCardParser_V21.sKnownPropertyNameSet;
    }

    protected Set<String> getKnownTypeSet() {
        return VCardParser_V21.sKnownTypeSet;
    }

    protected Set<String> getKnownValueSet() {
        return VCardParser_V21.sKnownValueSet;
    }

    protected Set<String> getAvailableEncodingSet() {
        return VCardParser_V21.sAvailableEncoding;
    }

    protected String getDefaultEncoding() {
        return DEFAULT_ENCODING;
    }


    public void parse(InputStream is, VCardInterpreter interpreter)
            throws IOException, VCardException {
        if (is == null) {
            throw new NullPointerException("InputStream must not be null.");
        }

        final InputStreamReader tmpReader = new InputStreamReader(is, mIntermediateCharset);
        mReader = new CustomBufferedReader(tmpReader);

        mInterpreter = (interpreter != null ? interpreter : new EmptyInterpreter());

        final long start = System.currentTimeMillis();
        if (mInterpreter != null) {
            mInterpreter.start();
        }
        parseVCardFile();
        if (mInterpreter != null) {
            mInterpreter.end();
        }
    }

    public final void cancel() {
        Log.i(LOG_TAG, "ParserImpl received cancel operation.");
        mCanceled = true;
    }
}
