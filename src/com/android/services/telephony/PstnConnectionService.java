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

import android.telephony.PhoneNumberUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.phone.Constants;
import com.android.services.telecomm.Connection;
import com.android.services.telecomm.ConnectionRequest;
import com.android.services.telecomm.Response;

import com.google.android.collect.Sets;

import java.util.Set;

/**
 * The parent class for PSTN-based call services. Handles shared functionality between all PSTN
 * call services.
 */
public abstract class PstnConnectionService extends TelephonyConnectionService {
    private EmergencyCallHelper mEmergencyCallHelper;
    private final Set<ConnectionRequest> mPendingOutgoingEmergencyCalls = Sets.newHashSet();

    @Override
    public void onCreate() {
        super.onCreate();
        mEmergencyCallHelper = new EmergencyCallHelper(this);
    }

    /** {@inheritDoc} */
    @Override
    public void onCreateConnections(
            final ConnectionRequest request,
            final Response<ConnectionRequest, Connection> response) {
        // TODO: Consider passing call emergency information as part of ConnectionRequest so
        // that we do not have to make the check here once again.
        String handle = request.getHandle().getSchemeSpecificPart();
        final Phone phone = getPhone();
        if (PhoneNumberUtils.isPotentialEmergencyNumber(handle)) {
            EmergencyCallHelper.Callback callback = new EmergencyCallHelper.Callback() {
                @Override
                public void onComplete(boolean isRadioReady) {
                    if (mPendingOutgoingEmergencyCalls.remove(request)) {
                        // The emergency call was still pending (not aborted) so continue with the
                        // rest of the logic.

                        if (isRadioReady) {
                            startCallWithPhone(phone, request, response);
                        } else {
                            responseError(request, response, "Failed to turn on radio.");
                        }
                    }
                }
            };

            mPendingOutgoingEmergencyCalls.add(request);

            // If the radio is already on, this will call us back fairly quickly.
            mEmergencyCallHelper.startTurnOnRadioSequence(phone, callback);
        } else {
            startCallWithPhone(phone, request, response);
        }
        super.onCreateConnections(request, response);
    }

    /** {@inheritDoc} */
    @Override
    public void onCreateIncomingConnection(
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> response) {
        Log.d(this, "onCreateIncomingConnection");
        Call call = getPhone().getRingingCall();

        // The ringing call is always not-null, check if it is truly ringing by checking its state.
        if (call.getState().isRinging()) {
            com.android.internal.telephony.Connection connection = call.getEarliestConnection();

            if (isConnectionKnown(connection)) {
                responseError(
                        request,
                        response,
                        "Cannot set incoming call ID, ringing connection already registered.");
            } else {
                // Address can be null for blocked calls.
                String address = connection.getAddress();
                if (address == null) {
                    address = "";
                }

                Uri handle = Uri.fromParts(Constants.SCHEME_TEL, address, null);

                TelephonyConnection telephonyConnection;
                try {
                    telephonyConnection = createTelephonyConnection(request, connection);
                } catch (Exception e) {
                    responseError(request, response, e.getMessage());
                    return;
                }

                responseResult(
                        new ConnectionRequest(handle, request.getExtras()),
                        response,
                        telephonyConnection);
            }
        } else {
            responseError(
                    request,
                    response,
                    String.format("Found no ringing call, call state: %s", call.getState()));
        }
        super.onCreateIncomingConnection(request, response);
    }

    /**
     * @return The current phone object behind this call service.
     */
    protected abstract Phone getPhone();
}
