/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.Context;
import android.telecomm.CallInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

/**
 * Call service that uses the GSM phone.
 */
public class GsmCallService extends PstnCallService {
    private static final String TAG = GsmCallService.class.getSimpleName();

    static boolean shouldSelect(Context context, CallInfo callInfo) {
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM;
    }

    /** {@inheritDoc} */
    @Override
    public void isCompatibleWith(CallInfo callInfo) {
        mCallServiceAdapter.setIsCompatibleWith(callInfo.getId(), shouldSelect(this, callInfo));
    }

    /** {@inheritDoc} */
    @Override
    protected Phone getPhone() {
        return CachedPhoneFactory.getGsmPhone();
    }
}
