/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.phone;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DefaultPhoneNotifier;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyListener;
import com.android.internal.telephony.IThirdPartyCallProvider;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.thirdpartyphone.ThirdPartyPhone;
import com.android.internal.telephony.uicc.IccIoResult;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.HexDump;
import com.android.services.telephony.common.Call;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the ITelephony interface.
 */
public class PhoneInterfaceManager extends ITelephony.Stub implements CallModeler.Listener {
    private static final String LOG_TAG = "PhoneInterfaceManager";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);
    private static final boolean DBG_LOC = false;

    // Message codes used with mMainThreadHandler
    private static final int CMD_HANDLE_PIN_MMI = 1;
    private static final int CMD_HANDLE_NEIGHBORING_CELL = 2;
    private static final int EVENT_NEIGHBORING_CELL_DONE = 3;
    private static final int CMD_ANSWER_RINGING_CALL = 4;
    private static final int CMD_END_CALL = 5;  // not used yet
    private static final int CMD_SILENCE_RINGER = 6;
    private static final int CMD_TRANSMIT_APDU = 7;
    private static final int EVENT_TRANSMIT_APDU_DONE = 8;
    private static final int CMD_OPEN_CHANNEL = 9;
    private static final int EVENT_OPEN_CHANNEL_DONE = 10;
    private static final int CMD_CLOSE_CHANNEL = 11;
    private static final int EVENT_CLOSE_CHANNEL_DONE = 12;
    // TODO: Remove this.
    private static final int CMD_NEW_INCOMING_THIRD_PARTY_CALL = 100;
    private static final int CMD_NV_READ_ITEM = 13;
    private static final int EVENT_NV_READ_ITEM_DONE = 14;
    private static final int CMD_NV_WRITE_ITEM = 15;
    private static final int EVENT_NV_WRITE_ITEM_DONE = 16;
    private static final int CMD_NV_WRITE_CDMA_PRL = 17;
    private static final int EVENT_NV_WRITE_CDMA_PRL_DONE = 18;
    private static final int CMD_NV_RESET_CONFIG = 19;
    private static final int EVENT_NV_RESET_CONFIG_DONE = 20;
    private static final int CMD_GET_PREFERRED_NETWORK_TYPE = 21;
    private static final int EVENT_GET_PREFERRED_NETWORK_TYPE_DONE = 22;
    private static final int CMD_SET_PREFERRED_NETWORK_TYPE = 23;
    private static final int EVENT_SET_PREFERRED_NETWORK_TYPE_DONE = 24;
    private static final int CMD_SEND_ENVELOPE = 25;
    private static final int EVENT_SEND_ENVELOPE_DONE = 26;

    /** The singleton instance. */
    private static PhoneInterfaceManager sInstance;

    PhoneGlobals mApp;
    Phone mPhone;
    CallManager mCM;
    AppOpsManager mAppOps;
    MainThreadHandler mMainThreadHandler;
    CallHandlerServiceProxy mCallHandlerService;
    CallModeler mCallModeler;
    DTMFTonePlayer mDtmfTonePlayer;
    Handler mDtmfStopHandler = new Handler();
    Runnable mDtmfStopRunnable;

    private final List<ITelephonyListener> mListeners = new ArrayList<ITelephonyListener>();
    private final Map<IBinder, TelephonyListenerDeathRecipient> mDeathRecipients =
            new HashMap<IBinder, TelephonyListenerDeathRecipient>();

    /**
     * A request object to use for transmitting data to an ICC.
     */
    private static final class IccAPDUArgument {
        public int channel, cla, command, p1, p2, p3;
        public String data;

        public IccAPDUArgument(int channel, int cla, int command,
                int p1, int p2, int p3, String data) {
            this.channel = channel;
            this.cla = cla;
            this.command = command;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.data = data;
        }
    }

    /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The argument to use for the request */
        public Object argument;
        /** The result of the request that is run on the main thread */
        public Object result;

        public MainThreadRequest(Object argument) {
            this.argument = argument;
        }
    }

    private static final class IncomingThirdPartyCallArgs {
        public final ComponentName component;
        public final String callId;
        public final String callerDisplayName;

        public IncomingThirdPartyCallArgs(ComponentName component, String callId,
                String callerDisplayName) {
            this.component = component;
            this.callId = callId;
            this.callerDisplayName = callerDisplayName;
        }
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.  The Binder thread
     * may provide a {@link MainThreadRequest} object in the msg.obj field that they are waiting
     * on, which will be notified when the operation completes and will contain the result of the
     * request.
     *
     * <p>If a MainThreadRequest object is provided in the msg.obj field,
     * note that request.result must be set to something non-null for the calling thread to
     * unblock.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            MainThreadRequest request;
            Message onCompleted;
            AsyncResult ar;

            switch (msg.what) {
                case CMD_HANDLE_PIN_MMI:
                    request = (MainThreadRequest) msg.obj;
                    request.result = mPhone.handlePinMmi((String) request.argument);
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_HANDLE_NEIGHBORING_CELL:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NEIGHBORING_CELL_DONE,
                            request);
                    mPhone.getNeighboringCids(onCompleted);
                    break;

                case EVENT_NEIGHBORING_CELL_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;
                    } else {
                        // create an empty list to notify the waiting thread
                        request.result = new ArrayList<NeighboringCellInfo>(0);
                    }
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_ANSWER_RINGING_CALL:
                    answerRingingCallInternal();
                    break;

                case CMD_SILENCE_RINGER:
                    silenceRingerInternal();
                    break;

                case CMD_END_CALL:
                    request = (MainThreadRequest) msg.obj;
                    boolean hungUp;
                    int phoneType = mPhone.getPhoneType();
                    if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                        // CDMA: If the user presses the Power button we treat it as
                        // ending the complete call session
                        hungUp = PhoneUtils.hangupRingingAndActive(mPhone);
                    } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                        // GSM: End the call as per the Phone state
                        hungUp = PhoneUtils.hangup(mCM);
                    } else {
                        throw new IllegalStateException("Unexpected phone type: " + phoneType);
                    }
                    if (DBG) log("CMD_END_CALL: " + (hungUp ? "hung up!" : "no call to hang up"));
                    request.result = hungUp;
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_NEW_INCOMING_THIRD_PARTY_CALL: {
                    request = (MainThreadRequest) msg.obj;
                    IncomingThirdPartyCallArgs args = (IncomingThirdPartyCallArgs) request.argument;
                    ThirdPartyPhone thirdPartyPhone = (ThirdPartyPhone)
                            PhoneUtils.getThirdPartyPhoneFromComponent(mCM, args.component);
                    thirdPartyPhone.takeIncomingCall(args.callId, args.callerDisplayName);
                    break;
                }

                case CMD_TRANSMIT_APDU:
                    request = (MainThreadRequest) msg.obj;
                    IccAPDUArgument argument = (IccAPDUArgument) request.argument;
                    onCompleted = obtainMessage(EVENT_TRANSMIT_APDU_DONE, request);
                    UiccController.getInstance().getUiccCard().iccTransmitApduLogicalChannel(
                            argument.channel, argument.cla, argument.command,
                            argument.p1, argument.p2, argument.p3, argument.data,
                            onCompleted);
                    break;

                case EVENT_TRANSMIT_APDU_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;
                    } else {
                        request.result = new IccIoResult(0x6F, 0, (byte[])null);
                        if (ar.result == null) {
                            loge("iccTransmitApduLogicalChannel: Empty response");
                        } else if (ar.exception instanceof CommandException) {
                            loge("iccTransmitApduLogicalChannel: CommandException: " +
                                    ar.exception);
                        } else {
                            loge("iccTransmitApduLogicalChannel: Unknown exception");
                        }
                    }
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_SEND_ENVELOPE:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_SEND_ENVELOPE_DONE, request);
                    UiccController.getInstance().getUiccCard().sendEnvelopeWithStatus(
                            (String)request.argument, onCompleted);
                    break;

                case EVENT_SEND_ENVELOPE_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;
                    } else {
                        request.result = new IccIoResult(0x6F, 0, (byte[])null);
                        if (ar.result == null) {
                            loge("sendEnvelopeWithStatus: Empty response");
                        } else if (ar.exception instanceof CommandException) {
                            loge("sendEnvelopeWithStatus: CommandException: " +
                                    ar.exception);
                        } else {
                            loge("sendEnvelopeWithStatus: exception:" + ar.exception);
                        }
                    }
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_OPEN_CHANNEL:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_OPEN_CHANNEL_DONE, request);
                    UiccController.getInstance().getUiccCard().iccOpenLogicalChannel(
                            (String)request.argument, onCompleted);
                    break;

                case EVENT_OPEN_CHANNEL_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ((int[]) ar.result)[0];
                    } else {
                        request.result = -1;
                        if (ar.result == null) {
                            loge("iccOpenLogicalChannel: Empty response");
                        } else if (ar.exception instanceof CommandException) {
                            loge("iccOpenLogicalChannel: CommandException: " +
                                    ar.exception);
                        } else {
                            loge("iccOpenLogicalChannel: Unknown exception");
                        }
                    }
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_CLOSE_CHANNEL:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_CLOSE_CHANNEL_DONE,
                            request);
                    UiccController.getInstance().getUiccCard().iccCloseLogicalChannel(
                            (Integer) request.argument,
                            onCompleted);
                    break;

                case EVENT_CLOSE_CHANNEL_DONE:
                    handleNullReturnEvent(msg, "iccCloseLogicalChannel");
                    break;

                case CMD_NV_READ_ITEM:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NV_READ_ITEM_DONE, request);
                    mPhone.nvReadItem((Integer) request.argument, onCompleted);
                    break;

                case EVENT_NV_READ_ITEM_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;     // String
                    } else {
                        request.result = "";
                        if (ar.result == null) {
                            loge("nvReadItem: Empty response");
                        } else if (ar.exception instanceof CommandException) {
                            loge("nvReadItem: CommandException: " +
                                    ar.exception);
                        } else {
                            loge("nvReadItem: Unknown exception");
                        }
                    }
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_NV_WRITE_ITEM:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NV_WRITE_ITEM_DONE, request);
                    Pair<Integer, String> idValue = (Pair<Integer, String>) request.argument;
                    mPhone.nvWriteItem(idValue.first, idValue.second, onCompleted);
                    break;

                case EVENT_NV_WRITE_ITEM_DONE:
                    handleNullReturnEvent(msg, "nvWriteItem");
                    break;

                case CMD_NV_WRITE_CDMA_PRL:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NV_WRITE_CDMA_PRL_DONE, request);
                    mPhone.nvWriteCdmaPrl((byte[]) request.argument, onCompleted);
                    break;

                case EVENT_NV_WRITE_CDMA_PRL_DONE:
                    handleNullReturnEvent(msg, "nvWriteCdmaPrl");
                    break;

                case CMD_NV_RESET_CONFIG:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NV_RESET_CONFIG_DONE, request);
                    mPhone.nvResetConfig((Integer) request.argument, onCompleted);
                    break;

                case EVENT_NV_RESET_CONFIG_DONE:
                    handleNullReturnEvent(msg, "nvResetConfig");
                    break;

                case CMD_GET_PREFERRED_NETWORK_TYPE:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_GET_PREFERRED_NETWORK_TYPE_DONE, request);
                    mPhone.getPreferredNetworkType(onCompleted);
                    break;

                case EVENT_GET_PREFERRED_NETWORK_TYPE_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;     // Integer
                    } else {
                        request.result = -1;
                        if (ar.result == null) {
                            loge("getPreferredNetworkType: Empty response");
                        } else if (ar.exception instanceof CommandException) {
                            loge("getPreferredNetworkType: CommandException: " +
                                    ar.exception);
                        } else {
                            loge("getPreferredNetworkType: Unknown exception");
                        }
                    }
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_SET_PREFERRED_NETWORK_TYPE:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_SET_PREFERRED_NETWORK_TYPE_DONE, request);
                    int networkType = (Integer) request.argument;
                    mPhone.setPreferredNetworkType(networkType, onCompleted);
                    break;

                case EVENT_SET_PREFERRED_NETWORK_TYPE_DONE:
                    handleNullReturnEvent(msg, "setPreferredNetworkType");
                    break;

                default:
                    Log.w(LOG_TAG, "MainThreadHandler: unexpected message code: " + msg.what);
                    break;
            }
        }

        private void handleNullReturnEvent(Message msg, String command) {
            AsyncResult ar = (AsyncResult) msg.obj;
            MainThreadRequest request = (MainThreadRequest) ar.userObj;
            if (ar.exception == null) {
                request.result = true;
            } else {
                request.result = false;
                if (ar.exception instanceof CommandException) {
                    loge(command + ": CommandException: " + ar.exception);
                } else {
                    loge(command + ": Unknown exception");
                }
            }
            synchronized (request) {
                request.notifyAll();
            }
        }
    }

    /**
     * Posts the specified command to be executed on the main thread,
     * waits for the request to complete, and returns the result.
     * @see #sendRequestAsync
     */
    private Object sendRequest(int command, Object argument) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread.");
        }

        MainThreadRequest request = new MainThreadRequest(argument);
        Message msg = mMainThreadHandler.obtainMessage(command, request);
        msg.sendToTarget();

        // Wait for the request to complete
        synchronized (request) {
            while (request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is complete
                }
            }
        }
        return request.result;
    }

    /**
     * Asynchronous ("fire and forget") version of sendRequest():
     * Posts the specified command to be executed on the main thread, and
     * returns immediately.
     * @see #sendRequest
     */
    private void sendRequestAsync(int command) {
        mMainThreadHandler.sendEmptyMessage(command);
    }

    /**
     * Same as {@link #sendRequestAsync(int)} except it takes an argument.
     * @see {@link #sendRequest(int,Object)}
     */
    private void sendRequestAsync(int command, Object argument) {
        MainThreadRequest request = new MainThreadRequest(argument);
        Message msg = mMainThreadHandler.obtainMessage(command, request);
        msg.sendToTarget();
    }

    /**
     * Initialize the singleton PhoneInterfaceManager instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static PhoneInterfaceManager init(PhoneGlobals app, Phone phone,
                CallHandlerServiceProxy callHandlerService, CallModeler callModeler,
                DTMFTonePlayer dtmfTonePlayer) {
        synchronized (PhoneInterfaceManager.class) {
            if (sInstance == null) {
                sInstance = new PhoneInterfaceManager(app, phone, callHandlerService, callModeler,
                        dtmfTonePlayer);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private PhoneInterfaceManager(PhoneGlobals app, Phone phone,
            CallHandlerServiceProxy callHandlerService, CallModeler callModeler,
            DTMFTonePlayer dtmfTonePlayer) {
        mApp = app;
        mPhone = phone;
        mCM = PhoneGlobals.getInstance().mCM;
        mAppOps = (AppOpsManager)app.getSystemService(Context.APP_OPS_SERVICE);
        mMainThreadHandler = new MainThreadHandler();
        mCallHandlerService = callHandlerService;
        mCallModeler = callModeler;
        mCallModeler.addListener(this);
        mDtmfTonePlayer = dtmfTonePlayer;
        publish();
    }

    private void publish() {
        if (DBG) log("publish: " + this);

        ServiceManager.addService("phone", this);
    }

    //
    // Implementation of the ITelephony interface.
    //

    public void dial(String number) {
        if (DBG) log("dial: " + number);
        // No permission check needed here: This is just a wrapper around the
        // ACTION_DIAL intent, which is available to any app since it puts up
        // the UI before it does anything.

        String url = createTelUrl(number);
        if (url == null) {
            return;
        }

        // PENDING: should we just silently fail if phone is offhook or ringing?
        PhoneConstants.State state = mCM.getState();
        if (state != PhoneConstants.State.OFFHOOK && state != PhoneConstants.State.RINGING) {
            Intent  intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mApp.startActivity(intent);
        }
    }

    public void call(String callingPackage, String number) {
        if (DBG) log("call: " + number);

        // This is just a wrapper around the ACTION_CALL intent, but we still
        // need to do a permission check since we're calling startActivity()
        // from the context of the phone app.
        enforceCallPermission();

        if (mAppOps.noteOp(AppOpsManager.OP_CALL_PHONE, Binder.getCallingUid(), callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        String url = createTelUrl(number);
        if (url == null) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mApp.startActivity(intent);
    }

    private boolean showCallScreenInternal(boolean specifyInitialDialpadState,
                                           boolean showDialpad) {
        if (!PhoneGlobals.sVoiceCapable) {
            // Never allow the InCallScreen to appear on data-only devices.
            return false;
        }
        if (isIdle()) {
            return false;
        }
        // If the phone isn't idle then go to the in-call screen
        long callingId = Binder.clearCallingIdentity();

        mCallHandlerService.bringToForeground(showDialpad);

        Binder.restoreCallingIdentity(callingId);
        return true;
    }

    // Show the in-call screen without specifying the initial dialpad state.
    public boolean showCallScreen() {
        return showCallScreenInternal(false, false);
    }

    // The variation of showCallScreen() that specifies the initial dialpad state.
    // (Ideally this would be called showCallScreen() too, just with a different
    // signature, but AIDL doesn't allow that.)
    public boolean showCallScreenWithDialpad(boolean showDialpad) {
        return showCallScreenInternal(true, showDialpad);
    }

    /**
     * End a call based on call state
     * @return true is a call was ended
     */
    public boolean endCall() {
        enforceCallPermission();
        return (Boolean) sendRequest(CMD_END_CALL, null);
    }

    public void answerRingingCall() {
        if (DBG) log("answerRingingCall...");
        // TODO: there should eventually be a separate "ANSWER_PHONE" permission,
        // but that can probably wait till the big TelephonyManager API overhaul.
        // For now, protect this call with the MODIFY_PHONE_STATE permission.
        enforceModifyPermission();
        sendRequestAsync(CMD_ANSWER_RINGING_CALL);
    }

    /**
     * Make the actual telephony calls to implement answerRingingCall().
     * This should only be called from the main thread of the Phone app.
     * @see #answerRingingCall
     *
     * TODO: it would be nice to return true if we answered the call, or
     * false if there wasn't actually a ringing incoming call, or some
     * other error occurred.  (In other words, pass back the return value
     * from PhoneUtils.answerCall() or PhoneUtils.answerAndEndActive().)
     * But that would require calling this method via sendRequest() rather
     * than sendRequestAsync(), and right now we don't actually *need* that
     * return value, so let's just return void for now.
     */
    private void answerRingingCallInternal() {
        final boolean hasRingingCall = !mPhone.getRingingCall().isIdle();
        if (hasRingingCall) {
            final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
            final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();
            if (hasActiveCall && hasHoldingCall) {
                // Both lines are in use!
                // TODO: provide a flag to let the caller specify what
                // policy to use if both lines are in use.  (The current
                // behavior is hardwired to "answer incoming, end ongoing",
                // which is how the CALL button is specced to behave.)
                PhoneUtils.answerAndEndActive(mCM, mCM.getFirstActiveRingingCall());
                return;
            } else {
                // answerCall() will automatically hold the current active
                // call, if there is one.
                PhoneUtils.answerCall(mCM.getFirstActiveRingingCall());
                return;
            }
        } else {
            // No call was ringing.
            return;
        }
    }

    public void silenceRinger() {
        if (DBG) log("silenceRinger...");
        // TODO: find a more appropriate permission to check here.
        // (That can probably wait till the big TelephonyManager API overhaul.
        // For now, protect this call with the MODIFY_PHONE_STATE permission.)
        enforceModifyPermission();
        sendRequestAsync(CMD_SILENCE_RINGER);
    }

    /**
     * Internal implemenation of silenceRinger().
     * This should only be called from the main thread of the Phone app.
     * @see #silenceRinger
     */
    private void silenceRingerInternal() {
        if ((mCM.getState() == PhoneConstants.State.RINGING)
            && mApp.notifier.isRinging()) {
            // Ringer is actually playing, so silence it.
            if (DBG) log("silenceRingerInternal: silencing...");
            mApp.notifier.silenceRinger();
        }
    }

    public boolean isOffhook() {
        return (mCM.getState() == PhoneConstants.State.OFFHOOK);
    }

    public boolean isRinging() {
        return (mCM.getState() == PhoneConstants.State.RINGING);
    }

    public boolean isIdle() {
        return (mCM.getState() == PhoneConstants.State.IDLE);
    }

    public boolean isSimPinEnabled() {
        enforceReadPermission();
        return (PhoneGlobals.getInstance().isSimPinEnabled());
    }

    public boolean supplyPin(String pin) {
        int [] resultArray = supplyPinReportResult(pin);
        return (resultArray[0] == PhoneConstants.PIN_RESULT_SUCCESS) ? true : false;
    }

    public boolean supplyPuk(String puk, String pin) {
        int [] resultArray = supplyPukReportResult(puk, pin);
        return (resultArray[0] == PhoneConstants.PIN_RESULT_SUCCESS) ? true : false;
    }

    /** {@hide} */
    public int[] supplyPinReportResult(String pin) {
        enforceModifyPermission();
        final UnlockSim checkSimPin = new UnlockSim(mPhone.getIccCard());
        checkSimPin.start();
        return checkSimPin.unlockSim(null, pin);
    }

    /** {@hide} */
    public int[] supplyPukReportResult(String puk, String pin) {
        enforceModifyPermission();
        final UnlockSim checkSimPuk = new UnlockSim(mPhone.getIccCard());
        checkSimPuk.start();
        return checkSimPuk.unlockSim(puk, pin);
    }

    /**
     * Helper thread to turn async call to SimCard#supplyPin into
     * a synchronous one.
     */
    private static class UnlockSim extends Thread {

        private final IccCard mSimCard;

        private boolean mDone = false;
        private int mResult = PhoneConstants.PIN_GENERAL_FAILURE;
        private int mRetryCount = -1;

        // For replies from SimCard interface
        private Handler mHandler;

        // For async handler to identify request type
        private static final int SUPPLY_PIN_COMPLETE = 100;

        public UnlockSim(IccCard simCard) {
            mSimCard = simCard;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (UnlockSim.this) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        switch (msg.what) {
                            case SUPPLY_PIN_COMPLETE:
                                Log.d(LOG_TAG, "SUPPLY_PIN_COMPLETE");
                                synchronized (UnlockSim.this) {
                                    mRetryCount = msg.arg1;
                                    if (ar.exception != null) {
                                        if (ar.exception instanceof CommandException &&
                                                ((CommandException)(ar.exception)).getCommandError()
                                                == CommandException.Error.PASSWORD_INCORRECT) {
                                            mResult = PhoneConstants.PIN_PASSWORD_INCORRECT;
                                        } else {
                                            mResult = PhoneConstants.PIN_GENERAL_FAILURE;
                                        }
                                    } else {
                                        mResult = PhoneConstants.PIN_RESULT_SUCCESS;
                                    }
                                    mDone = true;
                                    UnlockSim.this.notifyAll();
                                }
                                break;
                        }
                    }
                };
                UnlockSim.this.notifyAll();
            }
            Looper.loop();
        }

        /*
         * Use PIN or PUK to unlock SIM card
         *
         * If PUK is null, unlock SIM card with PIN
         *
         * If PUK is not null, unlock SIM card with PUK and set PIN code
         */
        synchronized int[] unlockSim(String puk, String pin) {

            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Message callback = Message.obtain(mHandler, SUPPLY_PIN_COMPLETE);

            if (puk == null) {
                mSimCard.supplyPin(pin, callback);
            } else {
                mSimCard.supplyPuk(puk, pin, callback);
            }

            while (!mDone) {
                try {
                    Log.d(LOG_TAG, "wait for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(LOG_TAG, "done");
            int[] resultArray = new int[2];
            resultArray[0] = mResult;
            resultArray[1] = mRetryCount;
            return resultArray;
        }
    }

    public void updateServiceLocation() {
        // No permission check needed here: this call is harmless, and it's
        // needed for the ServiceState.requestStateUpdate() call (which is
        // already intentionally exposed to 3rd parties.)
        mPhone.updateServiceLocation();
    }

    public boolean isRadioOn() {
        return mPhone.getServiceState().getVoiceRegState() != ServiceState.STATE_POWER_OFF;
    }

    public void toggleRadioOnOff() {
        enforceModifyPermission();
        mPhone.setRadioPower(!isRadioOn());
    }
    public boolean setRadio(boolean turnOn) {
        enforceModifyPermission();
        if ((mPhone.getServiceState().getVoiceRegState() != ServiceState.STATE_POWER_OFF) != turnOn) {
            toggleRadioOnOff();
        }
        return true;
    }
    public boolean setRadioPower(boolean turnOn) {
        enforceModifyPermission();
        mPhone.setRadioPower(turnOn);
        return true;
    }

    public boolean enableDataConnectivity() {
        enforceModifyPermission();
        ConnectivityManager cm =
                (ConnectivityManager)mApp.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.setMobileDataEnabled(true);
        return true;
    }

    public int enableApnType(String type) {
        enforceModifyPermission();
        return mPhone.enableApnType(type);
    }

    public int disableApnType(String type) {
        enforceModifyPermission();
        return mPhone.disableApnType(type);
    }

    public boolean disableDataConnectivity() {
        enforceModifyPermission();
        ConnectivityManager cm =
                (ConnectivityManager)mApp.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.setMobileDataEnabled(false);
        return true;
    }

    public boolean isDataConnectivityPossible() {
        return mPhone.isDataConnectivityPossible();
    }

    public boolean handlePinMmi(String dialString) {
        enforceModifyPermission();
        return (Boolean) sendRequest(CMD_HANDLE_PIN_MMI, dialString);
    }

    public void cancelMissedCallsNotification() {
        enforceModifyPermission();
        mApp.notificationMgr.cancelMissedCallNotification();
    }

    public int getCallState() {
        return DefaultPhoneNotifier.convertCallState(mCM.getState());
    }

    public int getDataState() {
        return DefaultPhoneNotifier.convertDataState(mPhone.getDataConnectionState());
    }

    public int getDataActivity() {
        return DefaultPhoneNotifier.convertDataActivityState(mPhone.getDataActivityState());
    }

    @Override
    public Bundle getCellLocation() {
        try {
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from ACCESS_COARSE_LOCATION since this
            // is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        if (checkIfCallerIsSelfOrForegroundUser()) {
            if (DBG_LOC) log("getCellLocation: is active user");
            Bundle data = new Bundle();
            mPhone.getCellLocation().fillInNotifierBundle(data);
            return data;
        } else {
            if (DBG_LOC) log("getCellLocation: suppress non-active user");
            return null;
        }
    }

    @Override
    public void enableLocationUpdates() {
        mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_LOCATION_UPDATES, null);
        mPhone.enableLocationUpdates();
    }

    @Override
    public void disableLocationUpdates() {
        mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_LOCATION_UPDATES, null);
        mPhone.disableLocationUpdates();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<NeighboringCellInfo> getNeighboringCellInfo(String callingPackage) {
        try {
            mApp.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check
            // for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from
            // ACCESS_COARSE_LOCATION since this is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        if (mAppOps.noteOp(AppOpsManager.OP_NEIGHBORING_CELLS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return null;
        }
        if (checkIfCallerIsSelfOrForegroundUser()) {
            if (DBG_LOC) log("getNeighboringCellInfo: is active user");

            ArrayList<NeighboringCellInfo> cells = null;

            try {
                cells = (ArrayList<NeighboringCellInfo>) sendRequest(
                        CMD_HANDLE_NEIGHBORING_CELL, null);
            } catch (RuntimeException e) {
                loge("getNeighboringCellInfo " + e);
            }
            return cells;
        } else {
            if (DBG_LOC) log("getNeighboringCellInfo: suppress non-active user");
            return null;
        }
    }


    @Override
    public List<CellInfo> getAllCellInfo() {
        try {
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from ACCESS_COARSE_LOCATION since this
            // is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        if (checkIfCallerIsSelfOrForegroundUser()) {
            if (DBG_LOC) log("getAllCellInfo: is active user");
            return mPhone.getAllCellInfo();
        } else {
            if (DBG_LOC) log("getAllCellInfo: suppress non-active user");
            return null;
        }
    }

    @Override
    public void setCellInfoListRate(int rateInMillis) {
        mPhone.setCellInfoListRate(rateInMillis);
    }

    @Override
    public void newIncomingThirdPartyCall(ComponentName component, String callId,
            String callerDisplayName) {
        // TODO(sail): Enforce that the component belongs to the calling package.
        if (DBG) {
            log("newIncomingThirdPartyCall: component: " + component + " callId: " + callId);
        }
        enforceCallPermission();
        sendRequestAsync(CMD_NEW_INCOMING_THIRD_PARTY_CALL, new IncomingThirdPartyCallArgs(
                component, callId, callerDisplayName));
    }

    //
    // Internal helper methods.
    //

    private static boolean checkIfCallerIsSelfOrForegroundUser() {
        boolean ok;

        boolean self = Binder.getCallingUid() == Process.myUid();
        if (!self) {
            // Get the caller's user id then clear the calling identity
            // which will be restored in the finally clause.
            int callingUser = UserHandle.getCallingUserId();
            long ident = Binder.clearCallingIdentity();

            try {
                // With calling identity cleared the current user is the foreground user.
                int foregroundUser = ActivityManager.getCurrentUser();
                ok = (foregroundUser == callingUser);
                if (DBG_LOC) {
                    log("checkIfCallerIsSelfOrForegoundUser: foregroundUser=" + foregroundUser
                            + " callingUser=" + callingUser + " ok=" + ok);
                }
            } catch (Exception ex) {
                if (DBG_LOC) loge("checkIfCallerIsSelfOrForegoundUser: Exception ex=" + ex);
                ok = false;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        } else {
            if (DBG_LOC) log("checkIfCallerIsSelfOrForegoundUser: is self");
            ok = true;
        }
        if (DBG_LOC) log("checkIfCallerIsSelfOrForegoundUser: ret=" + ok);
        return ok;
    }

    /**
     * Make sure the caller has the READ_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceReadPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE, null);
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    /**
     * Make sure the caller has the CALL_PHONE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceCallPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.CALL_PHONE, null);
    }

    /**
     * Make sure the caller has the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforcePrivilegedPhoneStatePermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                null);
    }

    /**
     * Make sure the caller has SIM_COMMUNICATION permission.
     *
     * @throws SecurityException if the caller does not have the required permission.
     */
    private void enforceSimCommunicationPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.SIM_COMMUNICATION, null);
    }

    private String createTelUrl(String number) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }

        return "tel:" + number;
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, "[PhoneIntfMgr] " + msg);
    }

    private static void loge(String msg) {
        Log.e(LOG_TAG, "[PhoneIntfMgr] " + msg);
    }

    public int getActivePhoneType() {
        return mPhone.getPhoneType();
    }

    /**
     * Returns the CDMA ERI icon index to display
     */
    public int getCdmaEriIconIndex() {
        return mPhone.getCdmaEriIconIndex();
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     */
    public int getCdmaEriIconMode() {
        return mPhone.getCdmaEriIconMode();
    }

    /**
     * Returns the CDMA ERI text,
     */
    public String getCdmaEriText() {
        return mPhone.getCdmaEriText();
    }

    /**
     * Returns true if CDMA provisioning needs to run.
     */
    public boolean needsOtaServiceProvisioning() {
        return mPhone.needsOtaServiceProvisioning();
    }

    /**
     * Returns the unread count of voicemails
     */
    public int getVoiceMessageCount() {
        return mPhone.getVoiceMessageCount();
    }

    /**
     * Returns the data network type
     *
     * @Deprecated to be removed Q3 2013 use {@link #getDataNetworkType}.
     */
    @Override
    public int getNetworkType() {
        return mPhone.getServiceState().getDataNetworkType();
    }

    /**
     * Returns the data network type
     */
    @Override
    public int getDataNetworkType() {
        return mPhone.getServiceState().getDataNetworkType();
    }

    /**
     * Returns the data network type
     */
    @Override
    public int getVoiceNetworkType() {
        return mPhone.getServiceState().getVoiceNetworkType();
    }

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return mPhone.getIccCard().hasIccCard();
    }

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link Phone#LTE_ON_CDMA_TRUE}
     */
    public int getLteOnCdmaMode() {
        return mPhone.getLteOnCdmaMode();
    }

    /**
     * @see android.telephony.TelephonyManager.WifiCallingChoices
     */
    public int getWhenToMakeWifiCalls() {
        return Settings.System.getInt(mPhone.getContext().getContentResolver(),
                Settings.System.WHEN_TO_MAKE_WIFI_CALLS, getWhenToMakeWifiCallsDefaultPreference());
    }

    /**
     * @see android.telephony.TelephonyManager.WifiCallingChoices
     */
    public void setWhenToMakeWifiCalls(int preference) {
        if (DBG) log("setWhenToMakeWifiCallsStr, storing setting = " + preference);
        Settings.System.putInt(mPhone.getContext().getContentResolver(),
                Settings.System.WHEN_TO_MAKE_WIFI_CALLS, preference);
    }

    private static int getWhenToMakeWifiCallsDefaultPreference() {
        // TODO(sail): Use a build property to choose this value.
        return TelephonyManager.WifiCallingChoices.ALWAYS_USE;
    }

    @Override
    public int iccOpenLogicalChannel(String AID) {
        enforceSimCommunicationPermission();

        if (DBG) log("iccOpenLogicalChannel: " + AID);
        Integer channel = (Integer)sendRequest(CMD_OPEN_CHANNEL, AID);
        if (DBG) log("iccOpenLogicalChannel: " + channel);
        return channel;
    }

    @Override
    public boolean iccCloseLogicalChannel(int channel) {
        enforceSimCommunicationPermission();

        if (DBG) log("iccCloseLogicalChannel: " + channel);
        if (channel < 0) {
          return false;
        }
        Boolean success = (Boolean)sendRequest(CMD_CLOSE_CHANNEL, channel);
        if (DBG) log("iccCloseLogicalChannel: " + success);
        return success;
    }

    @Override
    public String iccTransmitApduLogicalChannel(int channel, int cla,
            int command, int p1, int p2, int p3, String data) {
        enforceSimCommunicationPermission();

        if (DBG) {
            log("iccTransmitApduLogicalChannel: chnl=" + channel + " cla=" + cla +
                    " cmd=" + command + " p1=" + p1 + " p2=" + p2 + " p3=" + p3 +
                    " data=" + data);
        }

        if (channel < 0) {
            return "";
        }

        IccIoResult response = (IccIoResult)sendRequest(CMD_TRANSMIT_APDU,
                new IccAPDUArgument(channel, cla, command, p1, p2, p3, data));
        if (DBG) log("iccTransmitApduLogicalChannel: " + response);

        // If the payload is null, there was an error. Indicate that by returning
        // an empty string.
        if (response.payload == null) {
          return "";
        }

        // Append the returned status code to the end of the response payload.
        String s = Integer.toHexString(
                (response.sw1 << 8) + response.sw2 + 0x10000).substring(1);
        s = IccUtils.bytesToHexString(response.payload) + s;
        return s;
    }

    @Override
    public String sendEnvelopeWithStatus(String content) {
        enforceSimCommunicationPermission();

        IccIoResult response = (IccIoResult)sendRequest(CMD_SEND_ENVELOPE, content);
        if (response.payload == null) {
          return "";
        }

        // Append the returned status code to the end of the response payload.
        String s = Integer.toHexString(
                (response.sw1 << 8) + response.sw2 + 0x10000).substring(1);
        s = IccUtils.bytesToHexString(response.payload) + s;
        return s;
    }

    /**
     * Read one of the NV items defined in {@link com.android.internal.telephony.RadioNVItems}
     * and {@code ril_nv_items.h}. Used for device configuration by some CDMA operators.
     *
     * @param itemID the ID of the item to read
     * @return the NV item as a String, or null on error.
     */
    @Override
    public String nvReadItem(int itemID) {
        enforceModifyPermission();
        if (DBG) log("nvReadItem: item " + itemID);
        String value = (String) sendRequest(CMD_NV_READ_ITEM, itemID);
        if (DBG) log("nvReadItem: item " + itemID + " is \"" + value + '"');
        return value;
    }

    /**
     * Write one of the NV items defined in {@link com.android.internal.telephony.RadioNVItems}
     * and {@code ril_nv_items.h}. Used for device configuration by some CDMA operators.
     *
     * @param itemID the ID of the item to read
     * @param itemValue the value to write, as a String
     * @return true on success; false on any failure
     */
    @Override
    public boolean nvWriteItem(int itemID, String itemValue) {
        enforceModifyPermission();
        if (DBG) log("nvWriteItem: item " + itemID + " value \"" + itemValue + '"');
        Boolean success = (Boolean) sendRequest(CMD_NV_WRITE_ITEM,
                new Pair<Integer, String>(itemID, itemValue));
        if (DBG) log("nvWriteItem: item " + itemID + ' ' + (success ? "ok" : "fail"));
        return success;
    }

    /**
     * Update the CDMA Preferred Roaming List (PRL) in the radio NV storage.
     * Used for device configuration by some CDMA operators.
     *
     * @param preferredRoamingList byte array containing the new PRL
     * @return true on success; false on any failure
     */
    @Override
    public boolean nvWriteCdmaPrl(byte[] preferredRoamingList) {
        enforceModifyPermission();
        if (DBG) log("nvWriteCdmaPrl: value: " + HexDump.toHexString(preferredRoamingList));
        Boolean success = (Boolean) sendRequest(CMD_NV_WRITE_CDMA_PRL, preferredRoamingList);
        if (DBG) log("nvWriteCdmaPrl: " + (success ? "ok" : "fail"));
        return success;
    }

    /**
     * Perform the specified type of NV config reset.
     * Used for device configuration by some CDMA operators.
     *
     * @param resetType the type of reset to perform (1 == factory reset; 2 == NV-only reset)
     * @return true on success; false on any failure
     */
    @Override
    public boolean nvResetConfig(int resetType) {
        enforceModifyPermission();
        if (DBG) log("nvResetConfig: type " + resetType);
        Boolean success = (Boolean) sendRequest(CMD_NV_RESET_CONFIG, resetType);
        if (DBG) log("nvResetConfig: type " + resetType + ' ' + (success ? "ok" : "fail"));
        return success;
    }

    /**
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * @return the preferred network type, defined in RILConstants.java.
     */
    @Override
    public int getPreferredNetworkType() {
        enforceModifyPermission();
        if (DBG) log("getPreferredNetworkType");
        int[] result = (int[]) sendRequest(CMD_GET_PREFERRED_NETWORK_TYPE, null);
        int networkType = (result != null ? result[0] : -1);
        if (DBG) log("getPreferredNetworkType: " + networkType);
        return networkType;
    }

    /**
     * Set the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * @param networkType the preferred network type, defined in RILConstants.java.
     * @return true on success; false on any failure.
     */
    @Override
    public boolean setPreferredNetworkType(int networkType) {
        enforceModifyPermission();
        if (DBG) log("setPreferredNetworkType: type " + networkType);
        Boolean success = (Boolean) sendRequest(CMD_SET_PREFERRED_NETWORK_TYPE, networkType);
        if (DBG) log("setPreferredNetworkType: " + (success ? "ok" : "fail"));
        return success;
    }

    @Override
    public void toggleHold() {
        enforceModifyPermission();

        try {
            PhoneUtils.switchHoldingAndActive(mCM.getFirstActiveBgCall());
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error during toggleHold().", e);
        }
    }

    @Override
    public void merge() {
        enforceModifyPermission();

        try {
            if (PhoneUtils.okToMergeCalls(mCM)) {
                PhoneUtils.mergeCalls(mCM);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error during merge().", e);
        }
    }

    @Override
    public void swap() {
        enforceModifyPermission();

        try {
            PhoneUtils.swap();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error during swap().", e);
        }
    }

    @Override
    public void mute(boolean onOff) {
        enforceModifyPermission();

        try {
            PhoneUtils.setMute(onOff);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error during mute().", e);
        }
    }

    @Override
    public void playDtmfTone(char digit, boolean timedShortTone) {
        enforceModifyPermission();

        synchronized (mDtmfStopHandler) {
            try {
                mDtmfTonePlayer.playDtmfTone(digit, timedShortTone);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error playing DTMF tone.", e);
            }

            if (mDtmfStopRunnable != null) {
                mDtmfStopHandler.removeCallbacks(mDtmfStopRunnable);
            }
            mDtmfStopRunnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (mDtmfStopHandler) {
                        if (mDtmfStopRunnable == this) {
                            mDtmfTonePlayer.stopDtmfTone();
                            mDtmfStopRunnable = null;
                        }
                    }
                }
            };
            mDtmfStopHandler.postDelayed(mDtmfStopRunnable, 5000);
        }
    }

    @Override
    public void stopDtmfTone() {
        enforceModifyPermission();

        synchronized (mDtmfStopHandler) {
            try {
                mDtmfTonePlayer.stopDtmfTone();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error stopping DTMF tone.", e);
            }

            if (mDtmfStopRunnable != null) {
                mDtmfStopHandler.removeCallbacks(mDtmfStopRunnable);
                mDtmfStopRunnable = null;
            }
        }
    }

    @Override
    public void addListener(ITelephonyListener listener) {
        enforcePrivilegedPhoneStatePermission();

        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null.");
        }

        synchronized (mListeners) {
            IBinder listenerBinder = listener.asBinder();
            for (ITelephonyListener l : mListeners) {
                if (l.asBinder().equals(listenerBinder)) {
                    Log.w(LOG_TAG, "Listener already registered. Ignoring.");
                    return;
                }
            }
            mListeners.add(listener);
            mDeathRecipients.put(listener.asBinder(),
                    new TelephonyListenerDeathRecipient(listener.asBinder()));

            // update the new listener so they get the full call state immediately
            for (Call call : mCallModeler.getFullList()) {
                try {
                    notifyListenerOfCallLocked(call, listener);
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Error updating new listener. Ignoring.");
                    removeListenerInternal(listener);
                }
            }
        }
    }

    @Override
    public void removeListener(ITelephonyListener listener) {
        enforcePrivilegedPhoneStatePermission();

        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null.");
        }

        removeListenerInternal(listener);
    }

    private void removeListenerInternal(ITelephonyListener listener) {
        IBinder listenerBinder = listener.asBinder();

        synchronized (mListeners) {
            for (Iterator<ITelephonyListener> it = mListeners.iterator(); it.hasNext(); ) {
                ITelephonyListener nextListener = it.next();
                if (nextListener.asBinder().equals(listenerBinder)) {
                    TelephonyListenerDeathRecipient dr = mDeathRecipients.get(listener.asBinder());
                    if (dr != null) {
                        dr.unlinkDeathRecipient();
                    }
                    it.remove();
                }
            }
        }
    }

    /** CallModeler.Listener implementation **/

    @Override
    public void onDisconnect(Call call) {
        notifyListenersOfCall(call);
    }

    @Override
    public void onIncoming(Call call) {
        notifyListenersOfCall(call);
    }

    @Override
    public void onUpdate(List<Call> calls) {
        for (Call call : calls) {
            notifyListenersOfCall(call);
        }
    }

    @Override
    public void onPostDialAction(
            Connection.PostDialState state, int callId, String remainingChars, char c) { }

    private void notifyListenersOfCall(Call call) {
        synchronized (mListeners) {
            for (Iterator<ITelephonyListener> it = mListeners.iterator(); it.hasNext(); ) {
                ITelephonyListener listener = it.next();
                try {
                    notifyListenerOfCallLocked(call, listener);
                } catch (RemoteException e) {
                    TelephonyListenerDeathRecipient deathRecipient =
                            mDeathRecipients.get(listener.asBinder());
                    if (deathRecipient != null) {
                        deathRecipient.unlinkDeathRecipient();
                    }
                    it.remove();
                }
            }
        }
    }

    private void notifyListenerOfCallLocked(final Call call,final ITelephonyListener listener)
            throws RemoteException {
        if (Binder.isProxy(listener)) {
            listener.onUpdate(call.getCallId(), call.getState(), call.getNumber());
        } else {
            mMainThreadHandler.post(new Runnable() {

                @Override
                public void run() {
                    try {
                        listener.onUpdate(call.getCallId(), call.getState(), call.getNumber());
                    } catch (RemoteException e) {
                        Log.wtf(LOG_TAG, "Local binder call failed with RemoteException.", e);
                    }
                }
            });
        }

    }

    private class TelephonyListenerDeathRecipient implements Binder.DeathRecipient {
        private final IBinder mBinder;

        public TelephonyListenerDeathRecipient(IBinder listener) {
            mBinder = listener;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                unlinkDeathRecipient();
            }
        }

        @Override
        public void binderDied() {
            synchronized (mListeners) {
                if (mListeners.contains(mBinder)) {
                    mListeners.remove(mBinder);
                    Log.w(LOG_TAG, "ITelephonyListener died. Removing.");
                } else {
                    Log.w(LOG_TAG, "TelephonyListener binder died but the listener " +
                            "is not registered.");
                }
            }
        }

        public void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }
    }
}
