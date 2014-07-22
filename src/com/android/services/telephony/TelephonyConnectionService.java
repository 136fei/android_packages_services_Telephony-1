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

import android.content.ComponentName;
import android.net.Uri;
import android.telecomm.CallCapabilities;
import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.Response;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Phone;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;

import java.util.Objects;

/**
 * Service for making GSM and CDMA connections.
 */
public class TelephonyConnectionService extends ConnectionService {
    static String SCHEME_TEL = "tel";

    private ComponentName mExpectedComponentName = null;
    private EmergencyCallHelper mEmergencyCallHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        mExpectedComponentName = new ComponentName(this, this.getClass());
    }

    @Override
    public void onCreateOutgoingConnection(
            final ConnectionRequest request,
            final CreateConnectionResponse<Connection> response) {
        Log.v(this, "onCreateOutgoingConnection, request: " + request);

        Uri handle = request.getHandle();
        if (handle == null) {
            Log.d(this, "onCreateOutgoingConnection, handle is null");
            response.onFailure(request, DisconnectCause.NO_PHONE_NUMBER_SUPPLIED, "Handle is null");
            return;
        }

        if (!SCHEME_TEL.equals(handle.getScheme())) {
            Log.d(this, "onCreateOutgoingConnection, Handle %s is not type tel",
                    handle.getScheme());
            response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED,
                    "Handle scheme is not type tel");
            return;
        }

        final String number = handle.getSchemeSpecificPart();
        if (TextUtils.isEmpty(number)) {
            Log.d(this, "onCreateOutgoingConnection, unable to parse number");
            response.onFailure(request, DisconnectCause.INVALID_NUMBER, "Unable to parse number");
            return;
        }

        // Get the right phone object from the account data passed in.
        final Phone phone = getPhoneForAccount(request.getAccountHandle());
        if (phone == null) {
            Log.d(this, "onCreateOutgoingConnection, phone is null");
            response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED, "Phone is null");
            return;
        }

        boolean isEmergencyNumber = PhoneNumberUtils.isPotentialEmergencyNumber(number);
        if (!isEmergencyNumber) {
            int state = phone.getServiceState().getState();
            switch (state) {
                case ServiceState.STATE_IN_SERVICE:
                    break;
                case ServiceState.STATE_OUT_OF_SERVICE:
                    response.onFailure(request, DisconnectCause.OUT_OF_SERVICE,
                            "ServiceState.STATE_OUT_OF_SERVICE");
                    return;
                case ServiceState.STATE_EMERGENCY_ONLY:
                    response.onFailure(request, DisconnectCause.EMERGENCY_ONLY,
                            "ServiceState.STATE_EMERGENCY_ONLY");
                    return;
                case ServiceState.STATE_POWER_OFF:
                    response.onFailure(request, DisconnectCause.POWER_OFF,
                            "ServiceState.STATE_POWER_OFF");
                    return;
                default:
                    Log.d(this, "onCreateOutgoingConnection, unkown service state: %d", state);
                    response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED,
                            "Unkown service state " + state);
                    return;
            }
        }

        if (isEmergencyNumber) {
            Log.d(this, "onCreateOutgoingConnection, doing startTurnOnRadioSequence for " +
                    "emergency number");
            if (mEmergencyCallHelper == null) {
                mEmergencyCallHelper = new EmergencyCallHelper(this);
            }
            mEmergencyCallHelper.startTurnOnRadioSequence(phone,
                    new EmergencyCallHelper.Callback() {
                        @Override
                        public void onComplete(boolean isRadioReady) {
                            if (isRadioReady) {
                                startOutgoingCall(request, response, phone, number);
                            } else {
                                Log.d(this, "onCreateOutgoingConnection, failed to turn on radio");
                                response.onFailure(request, DisconnectCause.POWER_OFF,
                                        "Failed to turn on radio.");
                            }
                        }
            });
            return;
        }

        startOutgoingCall(request, response, phone, number);
    }

    @Override
    public void onCreateConferenceConnection(
            String token,
            Connection connection,
            Response<String, Connection> response) {
        Log.v(this, "onCreateConferenceConnection, connection: " + connection);
        if (connection instanceof GsmConnection || connection instanceof ConferenceConnection) {
            if ((connection.getCallCapabilities() & CallCapabilities.MERGE_CALLS) != 0) {
                response.onResult(token,
                        GsmConferenceController.createConferenceConnection(connection));
            }
        }
    }

    @Override
    public void onCreateIncomingConnection(
            ConnectionRequest request,
            CreateConnectionResponse<Connection> response) {
        Log.v(this, "onCreateIncomingConnection, request: " + request);

        Phone phone = getPhoneForAccount(request.getAccountHandle());
        if (phone == null) {
            response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED, null);
            return;
        }

        Call call = phone.getRingingCall();
        if (!call.getState().isRinging()) {
            Log.v(this, "onCreateIncomingConnection, no ringing call");
            response.onFailure(request, DisconnectCause.INCOMING_MISSED, "Found no ringing call");
            return;
        }

        com.android.internal.telephony.Connection originalConnection = call.getEarliestConnection();
        if (isOriginalConnectionKnown(originalConnection)) {
            Log.v(this, "onCreateIncomingConnection, original connection already registered");
            response.onCancel(request);
            return;
        }

        TelephonyConnection connection = null;
        if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
            connection = new GsmConnection(originalConnection);
        } else if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            connection = new CdmaConnection(originalConnection);
        }

        if (connection == null) {
            response.onCancel(request);
        } else {
            response.onSuccess(request, connection);
        }
    }

    @Override
    public void onConnectionAdded(Connection connection) {
        Log.v(this, "onConnectionAdded, connection: " + connection);
    }

    @Override
    public void onConnectionRemoved(Connection connection) {
        Log.v(this, "onConnectionRemoved, connection: " + connection);
        if (connection instanceof TelephonyConnection) {
            ((TelephonyConnection) connection).onRemovedFromCallService();
        }
    }

    private void startOutgoingCall(
            ConnectionRequest request,
            CreateConnectionResponse<Connection> response,
            Phone phone,
            String number) {
        Log.v(this, "startOutgoingCall");

        com.android.internal.telephony.Connection originalConnection;
        try {
            originalConnection = phone.dial(number, request.getVideoState());
        } catch (CallStateException e) {
            Log.e(this, e, "startOutgoingCall, phone.dial exception: " + e);
            response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED, e.getMessage());
            return;
        }

        if (originalConnection == null) {
            int disconnectCause = DisconnectCause.ERROR_UNSPECIFIED;

            // On GSM phones, null connection means that we dialed an MMI code
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
                disconnectCause = DisconnectCause.DIALED_MMI;
            }
            Log.d(this, "startOutgoingCall, phone.dial returned null");
            response.onFailure(request, disconnectCause, "Connection is null");
            return;
        }

        if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
            response.onSuccess(request, new GsmConnection(originalConnection));
        } else if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            response.onSuccess(request, new CdmaConnection(originalConnection));
        } else {
            // TODO(ihab): Tear down 'originalConnection' here, or move recognition of
            // getPhoneType() earlier in this method before we've already asked phone to dial()
            response.onFailure(request, DisconnectCause.ERROR_UNSPECIFIED, "Invalid phone type");
        }
    }

    private boolean isOriginalConnectionKnown(
            com.android.internal.telephony.Connection originalConnection) {
        for (Connection connection : getAllConnections()) {
            TelephonyConnection telephonyConnection = (TelephonyConnection) connection;
            if (connection instanceof TelephonyConnection) {
                if (telephonyConnection.getOriginalConnection() == originalConnection) {
                    return true;
                }
            }
        }
        return false;
    }

    private Phone getPhoneForAccount(PhoneAccountHandle accountHandle) {
        if (Objects.equals(mExpectedComponentName, accountHandle.getComponentName())) {
            int phoneId = SubscriptionController.getInstance().getPhoneId(
                    Long.parseLong(accountHandle.getId()));
            return PhoneFactory.getPhone(phoneId);
        }
        return null;
    }
}
