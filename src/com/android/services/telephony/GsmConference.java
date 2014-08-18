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

import android.telecomm.PhoneCapabilities;
import android.telecomm.Conference;
import android.telecomm.Connection;
import android.telecomm.PhoneAccountHandle;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;

import java.util.List;

/**
 * GSM-based conference call.
 */
public class GsmConference extends Conference {

    public GsmConference(PhoneAccountHandle phoneAccount) {
        super(phoneAccount);
        setCapabilities(
                PhoneCapabilities.SUPPORT_HOLD |
                PhoneCapabilities.HOLD |
                PhoneCapabilities.MUTE |
                PhoneCapabilities.SWAP_CALLS);
        setActive();
    }

    /**
     * Invoked when the Conference and all it's {@link Connection}s should be disconnected.
     */
    @Override
    public void onDisconnect() {
        for (Connection connection : getConnections()) {
            Call call = getMultipartyCallForConnection(connection, "onDisconnect");
            if (call != null) {
                try {
                    call.hangup();
                } catch (CallStateException e) {
                    Log.e(this, e, "Exception thrown trying to hangup conference");
                }
            }
        }
    }

    /**
     * Invoked when the specified {@link Connection} should be separated from the conference call.
     *
     * @param connection The connection to separate.
     */
    @Override
    public void onSeparate(Connection connection) {
        com.android.internal.telephony.Connection radioConnection =
                getOriginalConnection(connection, "onSeparate");
        try {
            radioConnection.separate();
        } catch (CallStateException e) {
            Log.e(this, e, "Exception thrown trying to separate a conference call");
        }
    }

    /**
     * Invoked when the conference should be put on hold.
     */
    @Override
    public void onHold() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty()) {
            return;
        }
        ((GsmConnection) connections.get(0)).performHold();
    }

    /**
     * Invoked when the conference should be moved from hold to active.
     */
    @Override
    public void onUnhold() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty()) {
            return;
        }
        ((GsmConnection) connections.get(0)).performUnhold();
    }

    private Call getMultipartyCallForConnection(Connection connection, String tag) {
        com.android.internal.telephony.Connection radioConnection =
                getOriginalConnection(connection, tag);
        if (radioConnection != null) {
            Call call = radioConnection.getCall();
            if (call != null && call.isMultiparty()) {
                return call;
            }
        }
        return null;
    }

    private com.android.internal.telephony.Connection getOriginalConnection(
            Connection connection, String tag) {

        if (connection instanceof GsmConnection) {
            return ((GsmConnection) connection).getOriginalConnection();
        } else {
            Log.e(this, null, "Non GSM connection found in a Gsm conference (%s)", tag);
            return null;
        }
    }
}
