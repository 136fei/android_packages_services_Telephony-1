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
package com.android.phone.vvm.omtp.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.provider.VoicemailContract;
import android.telecom.PhoneAccountHandle;
import android.telecom.Voicemail;
import android.telephony.SmsMessage;
import android.util.Log;

import com.android.internal.telephony.PhoneConstants;
import com.android.phone.PhoneUtils;
import com.android.phone.settings.VisualVoicemailSettingsUtil;
import com.android.phone.vvm.omtp.OmtpConstants;
import com.android.phone.vvm.omtp.sync.OmtpVvmSourceManager;
import com.android.phone.vvm.omtp.sync.OmtpVvmSyncService;
import com.android.phone.vvm.omtp.sync.VoicemailsQueryHelper;

/**
 * Receive SMS messages and send for processing by the OMTP visual voicemail source.
 */
public class OmtpMessageReceiver extends BroadcastReceiver {
    private static final String TAG = "OmtpMessageReceiver";

    private Context mContext;
    private PhoneAccountHandle mPhoneAccount;

    @Override
    public void onReceive(Context context, Intent intent) {
        mContext = context;
        mPhoneAccount = PhoneUtils.makePstnPhoneAccountHandle(
                intent.getExtras().getInt(PhoneConstants.PHONE_KEY));

        if (!VisualVoicemailSettingsUtil.getVisualVoicemailEnabled(mContext, mPhoneAccount)) {
            Log.v(TAG, "Received vvm message for disabled vvm source.");
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        StringBuilder messageBody = new StringBuilder();

        for (int i = 0; i < messages.length; i++) {
            if (messages[i].mWrappedSmsMessage != null) {
                messageBody.append(messages[i].getMessageBody());
            }
        }

        WrappedMessageData messageData = OmtpSmsParser.parse(messageBody.toString());
        if (messageData != null) {
            if (messageData.getPrefix() == OmtpConstants.SYNC_SMS_PREFIX) {
                SyncMessage message = new SyncMessage(messageData);
                processSync(message);
            } else if (messageData.getPrefix() == OmtpConstants.STATUS_SMS_PREFIX) {
                StatusMessage message = new StatusMessage(messageData);
                updateAccount(message);
            } else {
                Log.e(TAG, "This should never have happened");
            }
        }
        // Let this fall through: this is not a message we're interested in.
    }

    /**
     * A sync message has two purposes: to signal a new voicemail message, and to indicate the
     * voicemails on the server have changed remotely (usually through the TUI). Save the new
     * message to the voicemail provider if it is the former case and perform a full sync in the
     * latter case.
     *
     * @param message The sync message to extract data from.
     */
    private void processSync(SyncMessage message) {
        switch (message.getSyncTriggerEvent()) {
            case OmtpConstants.NEW_MESSAGE:
                Voicemail voicemail = Voicemail.createForInsertion(
                        message.getTimestampMillis(), message.getSender())
                        .setPhoneAccount(mPhoneAccount)
                        .setSourceData(message.getId())
                        .setDuration(message.getLength())
                        .setSourcePackage(mContext.getPackageName())
                        .build();
                VoicemailsQueryHelper queryHelper = new VoicemailsQueryHelper(mContext);
                queryHelper.insertIfUnique(voicemail);
                break;
            case OmtpConstants.MAILBOX_UPDATE:
                Intent serviceIntent = new Intent(mContext, OmtpVvmSyncService.class);
                serviceIntent.setAction(OmtpVvmSyncService.SYNC_DOWNLOAD_ONLY);
                serviceIntent.putExtra(OmtpVvmSyncService.EXTRA_PHONE_ACCOUNT, mPhoneAccount);
                mContext.startService(serviceIntent);
                break;
            case OmtpConstants.GREETINGS_UPDATE:
                // Not implemented in V1
                break;
           default:
               Log.e(TAG, "Unrecognized sync trigger event: " + message.getSyncTriggerEvent());
               break;
        }
    }

    private void updateAccount(StatusMessage message) {
        OmtpVvmSourceManager vvmSourceManager =
                OmtpVvmSourceManager.getInstance(mContext);
        VoicemailContract.Status.setStatus(mContext, mPhoneAccount,
                VoicemailContract.Status.CONFIGURATION_STATE_OK,
                VoicemailContract.Status.DATA_CHANNEL_STATE_OK,
                VoicemailContract.Status.NOTIFICATION_CHANNEL_STATE_OK);

        // Save the IMAP credentials in the corresponding account object so they are
        // persistent and can be retrieved.
        VisualVoicemailSettingsUtil.setSourceCredentialsFromStatusMessage(
                mContext,
                mPhoneAccount,
                message);

        // Add a phone state listener so that changes to the communication channels can be recorded.
        vvmSourceManager.addPhoneStateListener(mPhoneAccount);

        Intent serviceIntent = new Intent(mContext, OmtpVvmSyncService.class);
        serviceIntent.setAction(OmtpVvmSyncService.SYNC_FULL_SYNC);
        serviceIntent.putExtra(OmtpVvmSyncService.EXTRA_PHONE_ACCOUNT, mPhoneAccount);
        mContext.startService(serviceIntent);
    }
}