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
package com.android.phone.vvm.omtp.sync;

import android.content.Context;
import android.net.Network;
import android.net.Uri;
import android.provider.VoicemailContract;
import android.telecom.PhoneAccountHandle;
import android.telecom.Voicemail;
import android.text.TextUtils;
import com.android.phone.PhoneUtils;
import com.android.phone.settings.VisualVoicemailSettingsUtil;
import com.android.phone.vvm.omtp.ActivationTask;
import com.android.phone.vvm.omtp.OmtpEvents;
import com.android.phone.vvm.omtp.OmtpVvmCarrierConfigHelper;
import com.android.phone.vvm.omtp.VvmLog;
import com.android.phone.vvm.omtp.fetch.VoicemailFetchedCallback;
import com.android.phone.vvm.omtp.imap.ImapHelper;
import com.android.phone.vvm.omtp.imap.ImapHelper.InitializingException;
import com.android.phone.vvm.omtp.scheduling.BaseTask;
import com.android.phone.vvm.omtp.sync.VvmNetworkRequest.NetworkWrapper;
import com.android.phone.vvm.omtp.utils.PhoneAccountHandleConverter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sync OMTP visual voicemail.
 */
public class OmtpVvmSyncService {

    private static final String TAG = OmtpVvmSyncService.class.getSimpleName();

    /**
     * Signifies a sync with both uploading to the server and downloading from the server.
     */
    public static final String SYNC_FULL_SYNC = "full_sync";
    /**
     * Only upload to the server.
     */
    public static final String SYNC_UPLOAD_ONLY = "upload_only";
    /**
     * Only download from the server.
     */
    public static final String SYNC_DOWNLOAD_ONLY = "download_only";
    /**
     * Only download single voicemail transcription.
     */
    public static final String SYNC_DOWNLOAD_ONE_TRANSCRIPTION =
            "download_one_transcription";

    private final Context mContext;

    // Record the timestamp of the last full sync so that duplicate syncs can be reduced.
    private static final String LAST_FULL_SYNC_TIMESTAMP = "last_full_sync_timestamp";
    // Constant indicating that there has never been a full sync.
    public static final long NO_PRIOR_FULL_SYNC = -1;

    private VoicemailsQueryHelper mQueryHelper;

    public OmtpVvmSyncService(Context context) {
        mContext = context;
        mQueryHelper = new VoicemailsQueryHelper(mContext);
    }

    public void sync(BaseTask task, String action, PhoneAccountHandle phoneAccount,
            Voicemail voicemail) {
        VvmLog.log(TAG, "Sync requested: " + action +
                " for all accounts: " + String.valueOf(phoneAccount == null));
        if (phoneAccount != null) {
            VvmLog.v(TAG, "Sync requested: " + action + " - for account: " + phoneAccount);
            setupAndSendRequest(task, phoneAccount, voicemail, action);
        } else {
            VvmLog.v(TAG, "Sync requested: " + action + " - for all accounts");
            OmtpVvmSourceManager vvmSourceManager =
                    OmtpVvmSourceManager.getInstance(mContext);
            Set<PhoneAccountHandle> sources = vvmSourceManager.getOmtpVvmSources();
            for (PhoneAccountHandle source : sources) {
                setupAndSendRequest(task, source, null, action);
            }
        }
    }

    private void setupAndSendRequest(BaseTask task, PhoneAccountHandle phoneAccount,
            Voicemail voicemail, String action) {
        if (!VisualVoicemailSettingsUtil.isEnabled(mContext, phoneAccount)) {
            VvmLog.v(TAG, "Sync requested for disabled account");
            return;
        }
        int subId = PhoneAccountHandleConverter.toSubId(phoneAccount);
        if (!OmtpVvmSourceManager.getInstance(mContext).isVvmSourceRegistered(phoneAccount)) {
            ActivationTask.start(mContext, subId, null);
            return;
        }

        OmtpVvmCarrierConfigHelper config = new OmtpVvmCarrierConfigHelper(mContext, subId);
        try (NetworkWrapper network = VvmNetworkRequest.getNetwork(config, phoneAccount)) {
            if (network == null) {
                VvmLog.e(TAG, "unable to acquire network");
                task.fail();
                return;
            }
            doSync(task, network.get(), phoneAccount, voicemail, action);
        }
    }

    private void doSync(BaseTask task, Network network, PhoneAccountHandle phoneAccount,
            Voicemail voicemail, String action) {
        try(ImapHelper imapHelper = new ImapHelper(mContext, phoneAccount, network)) {
            boolean success;
            if (voicemail == null) {
                success = syncAll(action, imapHelper, phoneAccount);
            } else {
                success = syncOne(imapHelper, voicemail, phoneAccount);
            }
            imapHelper.updateQuota();

            if (success) {
                imapHelper.handleEvent(OmtpEvents.DATA_IMAP_OPERATION_COMPLETED);
            } else {
                task.fail();
            }
        } catch (InitializingException e) {
            VvmLog.w(TAG, "Can't retrieve Imap credentials.", e);
            return;
        }
    }

    private boolean syncAll(String action, ImapHelper imapHelper, PhoneAccountHandle account) {
        boolean uploadSuccess = true;
        boolean downloadSuccess = true;

        if (SYNC_FULL_SYNC.equals(action) || SYNC_UPLOAD_ONLY.equals(action)) {
            uploadSuccess = upload(imapHelper);
        }
        if (SYNC_FULL_SYNC.equals(action) || SYNC_DOWNLOAD_ONLY.equals(action)) {
            downloadSuccess = download(imapHelper, account);
        }

        VvmLog.v(TAG, "upload succeeded: [" + String.valueOf(uploadSuccess)
                + "] download succeeded: [" + String.valueOf(downloadSuccess) + "]");

        return uploadSuccess && downloadSuccess;
    }

    private boolean syncOne(ImapHelper imapHelper, Voicemail voicemail,
            PhoneAccountHandle account) {
        if (shouldPerformPrefetch(account, imapHelper)) {
            VoicemailFetchedCallback callback = new VoicemailFetchedCallback(mContext,
                    voicemail.getUri());
            imapHelper.fetchVoicemailPayload(callback, voicemail.getSourceData());
        }

        return imapHelper.fetchTranscription(
                new TranscriptionFetchedCallback(mContext, voicemail),
                voicemail.getSourceData());
    }

    private boolean upload(ImapHelper imapHelper) {
        List<Voicemail> readVoicemails = mQueryHelper.getReadVoicemails();
        List<Voicemail> deletedVoicemails = mQueryHelper.getDeletedVoicemails();

        boolean success = true;

        if (deletedVoicemails.size() > 0) {
            if (imapHelper.markMessagesAsDeleted(deletedVoicemails)) {
                // We want to delete selectively instead of all the voicemails for this provider
                // in case the state changed since the IMAP query was completed.
                mQueryHelper.deleteFromDatabase(deletedVoicemails);
            } else {
                success = false;
            }
        }

        if (readVoicemails.size() > 0) {
            if (imapHelper.markMessagesAsRead(readVoicemails)) {
                mQueryHelper.markReadInDatabase(readVoicemails);
            } else {
                success = false;
            }
        }

        return success;
    }

    private boolean download(ImapHelper imapHelper, PhoneAccountHandle account) {
        List<Voicemail> serverVoicemails = imapHelper.fetchAllVoicemails();
        List<Voicemail> localVoicemails = mQueryHelper.getAllVoicemails();

        if (localVoicemails == null || serverVoicemails == null) {
            // Null value means the query failed.
            return false;
        }

        Map<String, Voicemail> remoteMap = buildMap(serverVoicemails);

        // Go through all the local voicemails and check if they are on the server.
        // They may be read or deleted on the server but not locally. Perform the
        // appropriate local operation if the status differs from the server. Remove
        // the messages that exist both locally and on the server to know which server
        // messages to insert locally.
        for (int i = 0; i < localVoicemails.size(); i++) {
            Voicemail localVoicemail = localVoicemails.get(i);
            Voicemail remoteVoicemail = remoteMap.remove(localVoicemail.getSourceData());
            if (remoteVoicemail == null) {
                mQueryHelper.deleteFromDatabase(localVoicemail);
            } else {
                if (remoteVoicemail.isRead() != localVoicemail.isRead()) {
                    mQueryHelper.markReadInDatabase(localVoicemail);
                }

                if (!TextUtils.isEmpty(remoteVoicemail.getTranscription()) &&
                        TextUtils.isEmpty(localVoicemail.getTranscription())) {
                    mQueryHelper.updateWithTranscription(localVoicemail,
                            remoteVoicemail.getTranscription());
                }
            }
        }

        // The leftover messages are messages that exist on the server but not locally.
        boolean prefetchEnabled = shouldPerformPrefetch(account, imapHelper);
        for (Voicemail remoteVoicemail : remoteMap.values()) {
            Uri uri = VoicemailContract.Voicemails.insert(mContext, remoteVoicemail);
            if (prefetchEnabled) {
                VoicemailFetchedCallback fetchedCallback =
                        new VoicemailFetchedCallback(mContext, uri);
                imapHelper.fetchVoicemailPayload(fetchedCallback, remoteVoicemail.getSourceData());
            }
        }

        return true;
    }

    private boolean shouldPerformPrefetch(PhoneAccountHandle account, ImapHelper imapHelper) {
        OmtpVvmCarrierConfigHelper carrierConfigHelper = new OmtpVvmCarrierConfigHelper(
                mContext, PhoneUtils.getSubIdForPhoneAccountHandle(account));
        return carrierConfigHelper.isPrefetchEnabled() && !imapHelper.isRoaming();
    }

    /**
     * Builds a map from provider data to message for the given collection of voicemails.
     */
    private Map<String, Voicemail> buildMap(List<Voicemail> messages) {
        Map<String, Voicemail> map = new HashMap<String, Voicemail>();
        for (Voicemail message : messages) {
            map.put(message.getSourceData(), message);
        }
        return map;
    }

    public class TranscriptionFetchedCallback {

        private Context mContext;
        private Voicemail mVoicemail;

        public TranscriptionFetchedCallback(Context context, Voicemail voicemail) {
            mContext = context;
            mVoicemail = voicemail;
        }

        public void setVoicemailTranscription(String transcription) {
            VoicemailsQueryHelper queryHelper = new VoicemailsQueryHelper(mContext);
            queryHelper.updateWithTranscription(mVoicemail, transcription);
        }
    }
}
