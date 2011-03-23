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

import com.android.vcard.exception.VCardException;

import java.io.IOException;
import java.io.InputStream;

public abstract class VCardParser {
    /**
     * <p>
     * Parses the given stream and send the vCard data into {@link VCardInterpreter}.
     * </p>.
     * <p>
     * Note that vCard 2.1 specification allows "CHARSET" parameter, and some career sets
     * local encoding to it. For example, Japanese phone career uses Shift_JIS, which is
     * formally allowed in vCard 2.1, but not allowed in vCard 3.0. In vCard 2.1,
     * In some exreme case, it is allowed for vCard to have different charsets in one vCard.
     * </p>
     * <p>
     * We recommend you use {@link VCardSourceDetector} and detect which kind of source the
     * vCard comes from and explicitly specify a charset using the result.
     * </p>
     *
     * @param is The source to parse.
     * @param interpreter A {@link VCardInterpreter} object which used to construct data.
     * @throws IOException, VCardException
     * @deprecated use {@link #addInterpreter(VCardInterpreter)} and
     * {@link #parse(InputStream)}
     */
    @Deprecated
    public void parse(InputStream is, VCardInterpreter interpreter)
            throws IOException, VCardException {
        addInterpreter(interpreter);
        parse(is);
    }

    public abstract void addInterpreter(VCardInterpreter interpreter);
    public abstract void parse(InputStream is) throws IOException, VCardException;

    /**
     * <p>
     * Cancel parsing vCard. Useful when you want to stop the parse in the other threads.
     * </p>
     * <p>
     * Actual cancel is done after parsing the current vcard.
     * </p>
     */
    public abstract void cancel();
}
