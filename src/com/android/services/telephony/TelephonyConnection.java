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

import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telecomm.CallAudioState;
import android.telephony.DisconnectCause;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection.PostDialListener;
import com.android.internal.telephony.Phone;
import android.telecomm.Connection;

import java.util.List;
import java.util.Objects;

/**
 * Base class for CDMA and GSM connections.
 */
abstract class TelephonyConnection extends Connection {
    private static final int MSG_PRECISE_CALL_STATE_CHANGED = 1;
    private static final int MSG_RINGBACK_TONE = 2;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PRECISE_CALL_STATE_CHANGED:
                    Log.v(TelephonyConnection.this, "MSG_PRECISE_CALL_STATE_CHANGED");
                    updateState(false);
                    break;
                case MSG_RINGBACK_TONE:
                    Log.v(TelephonyConnection.this, "MSG_RINGBACK_TONE");
                    // TODO: This code assumes that there is only one connection in the foreground
                    // call, in other words, it punts on network-mediated conference calling.
                    if (getOriginalConnection() != getForegroundConnection()) {
                        Log.v(TelephonyConnection.this, "handleMessage, original connection is " +
                                "not foreground connection, skipping");
                        return;
                    }
                    setRequestingRingback((Boolean) ((AsyncResult) msg.obj).result);
                    break;
            }
        }
    };

    private final PostDialListener mPostDialListener = new PostDialListener() {
        @Override
        public void onPostDialWait() {
            Log.v(TelephonyConnection.this, "onPostDialWait");
            if (mOriginalConnection != null) {
                setPostDialWait(mOriginalConnection.getRemainingPostDialString());
            }
        }
    };

    private com.android.internal.telephony.Connection mOriginalConnection;
    private Call.State mOriginalConnectionState = Call.State.IDLE;

    protected TelephonyConnection(com.android.internal.telephony.Connection originalConnection) {
        Log.v(this, "new TelephonyConnection, originalConnection: " + originalConnection);
        mOriginalConnection = originalConnection;
        getPhone().registerForPreciseCallStateChanged(
                mHandler, MSG_PRECISE_CALL_STATE_CHANGED, null);
        getPhone().registerForRingbackTone(mHandler, MSG_RINGBACK_TONE, null);
        mOriginalConnection.addPostDialListener(mPostDialListener);
    }

    @Override
    protected void onSetAudioState(CallAudioState audioState) {
        // TODO: update TTY mode.
        if (getPhone() != null) {
            getPhone().setEchoSuppressionEnabled();
        }
    }

    @Override
    protected void onSetState(int state) {
        Log.v(this, "onSetState, state: " + Connection.stateToString(state));
    }

    @Override
    protected void onDisconnect() {
        Log.v(this, "onDisconnect");
        hangup(DisconnectCause.LOCAL);
    }

    @Override
    protected void onSeparate() {
        Log.v(this, "onSeparate");
        if (mOriginalConnection != null) {
            try {
                mOriginalConnection.separate();
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.separate failed with exception");
            }
        }
    }

    @Override
    protected void onAbort() {
        Log.v(this, "onAbort");
        hangup(DisconnectCause.LOCAL);
    }

    @Override
    protected void onHold() {
        Log.v(this, "onHold");
        // TODO(santoscordon): Can dialing calls be put on hold as well since they take up the
        // foreground call slot?
        if (Call.State.ACTIVE == mOriginalConnectionState) {
            Log.v(this, "Holding active call");
            try {
                Phone phone = mOriginalConnection.getCall().getPhone();
                Call ringingCall = phone.getRingingCall();

                // Although the method says switchHoldingAndActive, it eventually calls a RIL method
                // called switchWaitingOrHoldingAndActive. What this means is that if we try to put
                // a call on hold while a call-waiting call exists, it'll end up accepting the
                // call-waiting call, which is bad if that was not the user's intention. We are
                // cheating here and simply skipping it because we know any attempt to hold a call
                // while a call-waiting call is happening is likely a request from Telecomm prior to
                // accepting the call-waiting call.
                // TODO(santoscordon): Investigate a better solution. It would be great here if we
                // could "fake" hold by silencing the audio and microphone streams for this call
                // instead of actually putting it on hold.
                if (ringingCall.getState() != Call.State.WAITING) {
                    phone.switchHoldingAndActive();
                }

                // TODO(santoscordon): Cdma calls are slightly different.
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to put call on hold.");
            }
        } else {
            Log.w(this, "Cannot put a call that is not currently active on hold.");
        }
    }

    @Override
    protected void onUnhold() {
        Log.v(this, "onUnhold");
        if (Call.State.HOLDING == mOriginalConnectionState) {
            try {
                // TODO: This doesn't handle multiple calls across connection services yet
                mOriginalConnection.getCall().getPhone().switchHoldingAndActive();
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to release call from hold.");
            }
        } else {
            Log.w(this, "Cannot release a call that is not already on hold from hold.");
        }
    }

    @Override
    protected void onAnswer(int videoState) {
        Log.v(this, "onAnswer");
        // TODO(santoscordon): Tons of hairy logic is missing here around multiple active calls on
        // CDMA devices. See {@link CallManager.acceptCall}.

        if (isValidRingingCall() && getPhone() != null) {
            try {
                getPhone().acceptCall(videoState);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to accept call.");
            }
        }
    }

    @Override
    protected void onReject() {
        Log.v(this, "onReject");
        if (isValidRingingCall()) {
            hangup(DisconnectCause.INCOMING_REJECTED);
        }
        super.onReject();
    }

    @Override
    protected void onPostDialContinue(boolean proceed) {
        Log.v(this, "onPostDialContinue, proceed: " + proceed);
        if (mOriginalConnection != null) {
            if (proceed) {
                mOriginalConnection.proceedAfterWaitChar();
            } else {
                mOriginalConnection.cancelPostDial();
            }
        }
    }

    @Override
    protected void onSwapWithBackgroundCall() {
        Log.v(this, "onSwapWithBackgroundCall");
    }

    @Override
    protected void onChildrenChanged(List<Connection> children) {
        Log.v(this, "onChildrenChanged, children: " + children);
    }

    @Override
    protected void onPhoneAccountClicked() {
        Log.v(this, "onPhoneAccountClicked");
    }

    protected abstract int buildCallCapabilities();

    protected final void updateCallCapabilities(boolean force) {
        int newCallCapabilities = buildCallCapabilities();
        if (force || getCallCapabilities() != newCallCapabilities) {
            setCallCapabilities(newCallCapabilities);
        }
    }

    protected final void updateHandle(boolean force) {
        if (mOriginalConnection != null) {
            Uri handle = TelephonyConnectionService.getHandleFromAddress(
                    mOriginalConnection.getAddress());
            int presentation = mOriginalConnection.getNumberPresentation();
            if (force || !Objects.equals(handle, getHandle()) ||
                    presentation != getHandlePresentation()) {
                Log.v(this, "updateHandle, handle changed");
                setHandle(handle, presentation);
            }

            String name = mOriginalConnection.getCnapName();
            int namePresentation = mOriginalConnection.getCnapNamePresentation();
            if (force || !Objects.equals(name, getCallerDisplayName()) ||
                    namePresentation != getCallerDisplayNamePresentation()) {
                Log.v(this, "updateHandle, caller display name changed");
                setCallerDisplayName(name, namePresentation);
            }
        }
    }

    void onAddedToCallService() {
        updateState(false);
    }

    void onRemovedFromCallService() {
        // Subclass can override this to do cleanup.
    }

    private void hangup(int disconnectCause) {
        if (mOriginalConnection != null) {
            try {
                Call call = mOriginalConnection.getCall();
                if (call != null && !call.isMultiparty()) {
                    call.hangup();
                } else {
                    mOriginalConnection.hangup();
                }
                // Set state deliberately since we are going to close() and will no longer be
                // listening to state updates from mOriginalConnection
                setDisconnected(disconnectCause, null);
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.hangup failed with exception");
            }
        }
        close();
    }

    com.android.internal.telephony.Connection getOriginalConnection() {
        return mOriginalConnection;
    }

    protected Call getCall() {
        if (mOriginalConnection != null) {
            return mOriginalConnection.getCall();
        }
        return null;
    }

    Phone getPhone() {
        Call call = getCall();
        if (call != null) {
            return call.getPhone();
        }
        return null;
    }

    private com.android.internal.telephony.Connection getForegroundConnection() {
        if (getPhone() != null) {
            return getPhone().getForegroundCall().getEarliestConnection();
        }
        return null;
    }

    /**
     * Checks to see the original connection corresponds to an active incoming call. Returns false
     * if there is no such actual call, or if the associated call is not incoming (See
     * {@link Call.State#isRinging}).
     */
    private boolean isValidRingingCall() {
        if (getPhone() == null) {
            Log.v(this, "isValidRingingCall, phone is null");
            return false;
        }

        Call ringingCall = getPhone().getRingingCall();
        if (!ringingCall.getState().isRinging()) {
            Log.v(this, "isValidRingingCall, ringing call is not in ringing state");
            return false;
        }

        if (ringingCall.getEarliestConnection() != mOriginalConnection) {
            Log.v(this, "isValidRingingCall, ringing call connection does not match");
            return false;
        }

        Log.v(this, "isValidRingingCall, returning true");
        return true;
    }

    private void updateState(boolean force) {
        if (mOriginalConnection == null) {
            return;
        }

        Call.State newState = mOriginalConnection.getState();
        Log.v(this, "Update state from %s to %s for %s", mOriginalConnectionState, newState, this);
        if (force || mOriginalConnectionState != newState) {
            mOriginalConnectionState = newState;
            switch (newState) {
                case IDLE:
                    break;
                case ACTIVE:
                    setActive();
                    break;
                case HOLDING:
                    setOnHold();
                    break;
                case DIALING:
                case ALERTING:
                    setDialing();
                    break;
                case INCOMING:
                case WAITING:
                    setRinging();
                    break;
                case DISCONNECTED:
                    setDisconnected(mOriginalConnection.getDisconnectCause(), null);
                    close();
                    break;
                case DISCONNECTING:
                    break;
            }
        }
        updateCallCapabilities(force);
        updateHandle(force);
    }

    private void close() {
        Log.v(this, "close");
        if (getPhone() != null) {
            getPhone().unregisterForPreciseCallStateChanged(mHandler);
            getPhone().unregisterForRingbackTone(mHandler);
        }
        mOriginalConnection = null;
        setDestroyed();
    }
}
