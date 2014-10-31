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
 * limitations under the License
 */

package com.android.services.telephony;

import com.android.internal.telephony.PhoneConstants;

import android.net.Uri;
import android.telecom.ConferenceParticipant;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneCapabilities;

/**
 * Represents a participant in a conference call.
 */
public class ConferenceParticipantConnection extends Connection {

    /**
     * The endpoint URI For the conference participant.
     */
    private final Uri mEndpoint;

    /**
     * The connection which owns this participant.
     */
    private final Connection mParentConnection;

    /**
     * Creates a new instance.
     *
     * @param participant The conference participant to create the instance for.
     */
    public ConferenceParticipantConnection(
            Connection parentConnection, ConferenceParticipant participant) {

        mParentConnection = parentConnection;
        setAddress(participant.getHandle(), PhoneConstants.PRESENTATION_ALLOWED);
        setCallerDisplayName(participant.getDisplayName(), PhoneConstants.PRESENTATION_ALLOWED);

        mEndpoint = participant.getEndpoint();
        setCapabilities();
    }

    /**
     * Changes the state of the conference participant.
     *
     * @param newState The new state.
     */
    public void updateState(int newState) {
        switch (newState) {
            case STATE_INITIALIZING:
                setInitializing();
                break;
            case STATE_RINGING:
                setRinging();
                break;
            case STATE_DIALING:
                setDialing();
                break;
            case STATE_HOLDING:
                setOnHold();
                break;
            case STATE_ACTIVE:
                setActive();
                break;
            case STATE_DISCONNECTED:
                setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
                break;
            default:
                setActive();
        }
    }

    /**
     * Configures the {@link android.telecom.PhoneCapabilities} applicable to this connection.  A
     * conference participant can only be disconnected from a conference since there is not
     * actual connection to the participant which could be split from the conference.
     */
    private void setCapabilities() {
        int capabilities = PhoneCapabilities.DISCONNECT_FROM_CONFERENCE;
        setCallCapabilities(capabilities);
    }
}
