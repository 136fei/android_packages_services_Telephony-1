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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;

import android.telephony.DisconnectCause;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;

/**
 * Manages a single phone call handled by the PSTN infrastructure.
 */
public abstract class PstnConnection extends TelephonyConnection {

    private static final int EVENT_RINGBACK_TONE = 1;

    private final Handler mHandler = new Handler() {
        private Connection getForegroundConnection() {
            return mPhone.getForegroundCall().getEarliestConnection();
        }

        public void handleMessage(Message msg) {
            // TODO: This code assumes that there is only one connection in the foreground call,
            // in other words, it punts on network-mediated conference calling.
            if (getOriginalConnection() != getForegroundConnection()) {
                return;
            }
            switch (msg.what) {
                case EVENT_RINGBACK_TONE:
                    setRequestingRingback((Boolean) ((AsyncResult) msg.obj).result);
                    break;
            }
        }
    };

    private final Phone mPhone;

    public PstnConnection(Phone phone, Connection connection) {
        super(connection);
        mPhone = phone;
        phone.registerForRingbackTone(mHandler, EVENT_RINGBACK_TONE, null);
    }

    /** {@inheritDoc} */
    @Override
    protected void onAnswer() {
        // TODO(santoscordon): Tons of hairy logic is missing here around multiple active calls on
        // CDMA devices. See {@link CallManager.acceptCall}.

        Log.i(this, "Answer call.");
        if (isValidRingingCall(getOriginalConnection())) {
            try {
                mPhone.acceptCall();
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to accept call.");
            }
        }
        super.onAnswer();
    }

    /** {@inheritDoc} */
    @Override
    protected void onReject() {
        Log.i(this, "Reject call.");
        if (isValidRingingCall(getOriginalConnection())) {
            hangup(DisconnectCause.INCOMING_REJECTED);
        }
        super.onReject();
    }

    /** {@inheritDoc} */
    @Override
    protected void onDisconnect() {
        mPhone.unregisterForRingbackTone(mHandler);
        super.onDisconnect();
    }

    @Override
    public void onPostDialContinue(boolean proceed) {
        if (proceed) {
            getOriginalConnection().proceedAfterWaitChar();
        } else {
            getOriginalConnection().cancelPostDial();
        }
    }

    protected Phone getPhone() {
        return mPhone;
    }

    /**
     * Checks to see if the specified low-level Telephony {@link Connection} corresponds to an
     * active incoming call. Returns false if there is no such actual call, or if the
     * associated call is not incoming (See {@link Call.State#isRinging}).
     *
     * @param connection The connection to ask about.
     */
    private boolean isValidRingingCall(Connection connection) {
        Call ringingCall = mPhone.getRingingCall();

        if (ringingCall.getState().isRinging()) {
            // The ringingCall object is always not-null so we have to check its current state.
            if (ringingCall.getEarliestConnection() == connection) {
                // The ringing connection is the same one for this call. We have a match!
                return true;
            } else {
                Log.w(this, "A ringing connection exists, but it is not the same connection.");
            }
        } else {
            Log.i(this, "There is no longer a ringing call.");
        }

        return false;
    }
}
