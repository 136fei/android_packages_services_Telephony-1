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
package com.android.phone.vvm.omtp.imap;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.telecom.Voicemail;

import com.android.phone.common.mail.Address;
import com.android.phone.common.mail.BodyPart;
import com.android.phone.common.mail.FetchProfile;
import com.android.phone.common.mail.Flag;
import com.android.phone.common.mail.Message;
import com.android.phone.common.mail.MessagingException;
import com.android.phone.common.mail.Multipart;
import com.android.phone.common.mail.TempDirectory;
import com.android.phone.common.mail.internet.MimeMessage;
import com.android.phone.common.mail.store.ImapFolder;
import com.android.phone.common.mail.store.ImapStore;
import com.android.phone.common.mail.store.imap.ImapConstants;
import com.android.phone.common.mail.utils.LogUtils;
import com.android.phone.vvm.omtp.OmtpConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A helper interface to abstract commands sent across IMAP interface for a given account.
 */
public class ImapHelper {
    private final String TAG = "ImapHelper";

    private ImapFolder mFolder;
    private ImapStore mImapStore;
    private Context mContext;

    public ImapHelper(Context context, Account account) {
        try {
            mContext = context;
            TempDirectory.setTempDirectory(context);

            AccountManager accountManager = AccountManager.get(context);
            String username = accountManager.getUserData(account, OmtpConstants.IMAP_USER_NAME);
            String password = accountManager.getUserData(account, OmtpConstants.IMAP_PASSWORD);
            String serverName = accountManager.getUserData(account, OmtpConstants.SERVER_ADDRESS);
            int port = Integer.parseInt(
                    accountManager.getUserData(account, OmtpConstants.IMAP_PORT));
            // TODO: determine the security protocol (e.g. ssl, tls, none, etc.)
            mImapStore = new ImapStore(
                    context, username, password, port, serverName,
                    ImapStore.FLAG_TLS);
        } catch (NumberFormatException e) {
            LogUtils.e(TAG, e, "Could not parse port number");
        }
    }

    /** The caller thread will block until the method returns. */
    public boolean markMessagesAsRead(List<Voicemail> voicemails) {
        return setFlags(voicemails, Flag.SEEN);
    }

    /** The caller thread will block until the method returns. */
    public boolean markMessagesAsDeleted(List<Voicemail> voicemails) {
        return setFlags(voicemails, Flag.DELETED);
    }

    /**
     * Set flags on the server for a given set of voicemails.
     *
     * @param voicemails The voicemails to set flags for.
     * @param flags The flags to set on the voicemails.
     * @return {@code true} if the operation completes successfully, {@code false} otherwise.
     */
    private boolean setFlags(List<Voicemail> voicemails, String... flags) {
        if (voicemails.size() == 0) {
            return false;
        }
        try {
            mFolder = openImapFolder(ImapFolder.MODE_READ_WRITE);
            if (mFolder != null) {
                mFolder.setFlags(convertToImapMessages(voicemails), flags, true);
                return true;
            }
            return false;
        } catch (MessagingException e) {
            LogUtils.e(TAG, e, "Messaging exception");
            return false;
        } finally {
            closeImapFolder();
        }
    }

    /**
     * Fetch a list of voicemails from the server.
     *
     * @return A list of voicemail objects containing data about voicemails stored on the server.
     */
    public List<Voicemail> fetchAllVoicemails() {
        List<Voicemail> result = new ArrayList<Voicemail>();
        Message[] messages;
        try {
            mFolder = openImapFolder(ImapFolder.MODE_READ_WRITE);
            if (mFolder == null) {
                // This means we were unable to successfully open the folder.
                return null;
            }

            // This method retrieves lightweight messages containing only the uid of the message.
            messages = mFolder.getMessages(null);

            for (Message message : messages) {
                // Get the voicemail details.
                Voicemail voicemail = fetchVoicemail(message);
                if (voicemail != null) {
                    result.add(voicemail);
                }
            }
            return result;
        } catch (MessagingException e) {
            LogUtils.e(TAG, e, "Messaging Exception");
            return null;
        } finally {
            closeImapFolder();
        }
    }

    /**
     * Fetches the structure of the given message and returns the voicemail parsed from it.
     *
     * @throws MessagingException if fetching the structure of the message fails
     */
    private Voicemail fetchVoicemail(Message message)
            throws MessagingException {
        LogUtils.d(TAG, "Fetching message structure for " + message.getUid());

        MessageStructureFetchedListener listener = new MessageStructureFetchedListener();

        FetchProfile fetchProfile = new FetchProfile();
        fetchProfile.addAll(Arrays.asList(FetchProfile.Item.FLAGS, FetchProfile.Item.ENVELOPE,
                FetchProfile.Item.STRUCTURE));

        // The IMAP folder fetch method will call "messageRetrieved" on the listener when the
        // message is successfully retrieved.
        mFolder.fetch(new Message[] {message}, fetchProfile, listener);
        return listener.getVoicemail();
    }

    /**
     * Listener for the message structure being fetched.
     */
    private final class MessageStructureFetchedListener
            implements ImapFolder.MessageRetrievalListener {
        private Voicemail mVoicemail;

        public MessageStructureFetchedListener() {
        }

        public Voicemail getVoicemail() {
            return mVoicemail;
        }

        @Override
        public void messageRetrieved(Message message) {
            LogUtils.d(TAG, "Fetched message structure for " + message.getUid());
            LogUtils.d(TAG, "Message retrieved: " + message);
            try {
                mVoicemail = getVoicemailFromMessage(message);
                if (mVoicemail == null) {
                    LogUtils.d(TAG, "This voicemail does not have an attachment...");
                    return;
                }
            } catch (MessagingException e) {
                LogUtils.e(TAG, e, "Messaging Exception");
                closeImapFolder();
            }
        }

        @Override
        public void loadAttachmentProgress(int progress) {}

        /**
         * Convert an IMAP message to a voicemail object.
         *
         * @param message The IMAP message.
         * @return The voicemail object corresponding to an IMAP message.
         * @throws MessagingException
         */
        private Voicemail getVoicemailFromMessage(Message message) throws MessagingException {
            if (!message.getMimeType().startsWith("multipart/")) {
                LogUtils.w(TAG, "Ignored non multi-part message");
                return null;
            }
            Multipart multipart = (Multipart) message.getBody();

            LogUtils.d(TAG, "Num body parts: " + multipart.getCount());

            for (int i = 0; i < multipart.getCount(); ++i) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String bodyPartMimeType = bodyPart.getMimeType().toLowerCase();

                LogUtils.d(TAG, "bodyPart mime type: " + bodyPartMimeType);

                if (bodyPartMimeType.startsWith("audio/")) {
                    // Found an audio attachment, this is a valid voicemail.
                    long time = message.getSentDate().getTime();
                    String number = getNumber(message.getFrom());
                    boolean isRead = Arrays.asList(message.getFlags()).contains(Flag.SEEN);

                    return Voicemail.createForInsertion(time, number)
                            .setSourcePackage(mContext.getPackageName())
                            .setSourceData(message.getUid())
                            .setIsRead(isRead)
                            .build();
                }
            }
            // No attachment found, this is not a voicemail.
            return null;
        }

        /**
         * The "from" field of a visual voicemail IMAP message is the number of the caller who left
         * the message. Extract this number from the list of "from" addresses.
         *
         * @param fromAddresses A list of addresses that comprise the "from" line.
         * @return The number of the voicemail sender.
         */
        private String getNumber(Address[] fromAddresses) {
            if (fromAddresses != null && fromAddresses.length > 0) {
                if (fromAddresses.length != 1) {
                    LogUtils.w(TAG, "More than one from addresses found. Using the first one.");
                }
                String sender = fromAddresses[0].getAddress();
                int atPos = sender.indexOf('@');
                if (atPos != -1) {
                    // Strip domain part of the address.
                    sender = sender.substring(0, atPos);
                }
                return sender;
            }
            return null;
        }
    }

    private ImapFolder openImapFolder(String modeReadWrite) {
        try {
            ImapFolder folder = new ImapFolder(mImapStore, ImapConstants.INBOX);
            folder.open(modeReadWrite);
            return folder;
        } catch (MessagingException e) {
            LogUtils.e(TAG, e, "Messaging Exception");
        }
        return null;
    }

    private Message[] convertToImapMessages(List<Voicemail> voicemails) {
        Message[] messages = new Message[voicemails.size()];
        for (int i = 0; i < voicemails.size(); ++i) {
            messages[i] = new MimeMessage();
            messages[i].setUid(voicemails.get(i).getSourceData());
        }
        return messages;
    }

    private void closeImapFolder() {
        if (mFolder != null) {
            mFolder.close(true);
        }
    }
}