/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.phone.vvm.omtp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.UserManager;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.phone.settings.VisualVoicemailSettingsUtil;
import com.android.phone.vvm.omtp.sync.OmtpVvmSourceManager;
import com.android.phone.vvm.omtp.utils.PhoneAccountHandleConverter;

/**
 * This class listens to the {@link CarrierConfigManager#ACTION_CARRIER_CONFIG_CHANGED} and {@link
 * TelephonyIntents#ACTION_SIM_STATE_CHANGED} to determine when a SIM is added, replaced, or
 * removed.
 *
 * When a SIM is added, send an activate SMS. When a SIM is removed, remove the sync accounts and
 * change the status in the voicemail_status table.
 */
public class SimChangeReceiver extends BroadcastReceiver {

    private static final String TAG = "VvmSimChangeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            Log.v(TAG, "Received broadcast for user that is not system.");
            return;
        }

        final String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "Null action for intent.");
            return;
        }

        switch (action) {
            case TelephonyIntents.ACTION_SIM_STATE_CHANGED:
                if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE))) {
                    Log.i(TAG, "Sim removed, removing inactive accounts");
                    OmtpVvmSourceManager.getInstance(context).removeInactiveSources();
                }
                break;
            case CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED:
                int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);

                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    Log.i(TAG, "Received SIM change for invalid subscription id.");
                    return;
                }
                Log.d(TAG, "Carrier config changed");
                if (!UserManager.get(context).isUserUnlocked()) {
                    Log.d(TAG, "User locked, activation request delayed until unlock");
                    OmtpBootCompletedReceiver.addDeferredSubId(context, subId);
                } else {
                    try {
                        processSubId(context, subId);
                    } catch (IllegalArgumentException e) {
                        // b/29358683 for some unknown reason UserManager.isUserUnlocked() could
                        // return true even if the device is not unlocked, which will cause a
                        // IllegalArgumentException when trying to write voicemail status.
                        // Catch it as a workaround while investigating.
                        Log.e(TAG, e.toString());
                        Log.e(TAG, "UserManager.isUserUnlocked: " + UserManager.get(context)
                            .isUserUnlocked());
                        PhoneAccountHandle phoneAccount = PhoneAccountHandleConverter
                            .fromSubId(subId);
                        OmtpVvmSourceManager.getInstance(context)
                            .removePhoneStateListener(phoneAccount);
                        OmtpBootCompletedReceiver.addDeferredSubId(context, subId);
                    }
                }
                break;
        }
    }

    public static void processSubId(Context context, int subId) {
        OmtpVvmCarrierConfigHelper carrierConfigHelper =
                new OmtpVvmCarrierConfigHelper(context, subId);
        if (carrierConfigHelper.isValid()) {
            PhoneAccountHandle phoneAccount = PhoneAccountHandleConverter.fromSubId(subId);

            if (VisualVoicemailSettingsUtil.isVisualVoicemailEnabled(context, phoneAccount)) {
                Log.i(TAG, "Sim state or carrier config changed: requesting"
                        + " activation for " + phoneAccount.getId());

                // Add a phone state listener so that changes to the communication channels
                // can be recorded.
                OmtpVvmSourceManager.getInstance(context).addPhoneStateListener(
                        phoneAccount);
                carrierConfigHelper.startActivation();
            } else {
                if (carrierConfigHelper.isLegacyModeEnabled()) {
                    // SMS still need to be filtered under legacy mode.
                    carrierConfigHelper.activateSmsFilter();
                }
                // It may be that the source was not registered to begin with but we want
                // to run through the steps to remove the source just in case.
                OmtpVvmSourceManager.getInstance(context).removeSource(phoneAccount);
                Log.v(TAG, "Sim change for disabled account.");
            }
        } else {
            String mccMnc = context.getSystemService(TelephonyManager.class).getSimOperator(subId);
            Log.d(TAG,
                    "visual voicemail not supported for carrier " + mccMnc + " on subId " + subId);
        }
    }
}