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
package com.android.phone.vvm.omtp;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.vvm.omtp.sms.OmtpCvvmMessageSender;
import com.android.phone.vvm.omtp.sms.OmtpMessageSender;
import com.android.phone.vvm.omtp.sms.OmtpStandardMessageSender;

import java.util.Arrays;
import java.util.Set;

/**
 * Manages carrier dependent visual voicemail configuration values.
 * The primary source is the value retrieved from CarrierConfigManager. If CarrierConfigManager does
 * not provide the config (KEY_VVM_TYPE_STRING is empty, or "hidden" configs), then the value
 * hardcoded in telephony will be used (in res/xml/vvm_config.xml)
 *
 * Hidden configs are new configs that are planned for future APIs, or miscellaneous settings that
 * may clutter CarrierConfigManager too much.
 *
 * The current hidden configs are:
 * {@link #getSslPort()}
 * {@link #getDisabledCapabilities()}
 */
public class OmtpVvmCarrierConfigHelper {

    private static final String TAG = "OmtpVvmCarrierCfgHlpr";

    static final String KEY_VVM_TYPE_STRING = CarrierConfigManager.KEY_VVM_TYPE_STRING;
    static final String KEY_VVM_DESTINATION_NUMBER_STRING =
            CarrierConfigManager.KEY_VVM_DESTINATION_NUMBER_STRING;
    static final String KEY_VVM_PORT_NUMBER_INT =
            CarrierConfigManager.KEY_VVM_PORT_NUMBER_INT;
    static final String KEY_CARRIER_VVM_PACKAGE_NAME_STRING =
            CarrierConfigManager.KEY_CARRIER_VVM_PACKAGE_NAME_STRING;
    static final String KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY =
            "carrier_vvm_package_name_string_array";
    static final String KEY_VVM_PREFETCH_BOOL =
            CarrierConfigManager.KEY_VVM_PREFETCH_BOOL;
    static final String KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL =
            CarrierConfigManager.KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL;
    static final String KEY_VVM_SSL_PORT_NUMBER_INT =
            "vvm_ssl_port_number_int";
    static final String KEY_VVM_DISABLED_CAPABILITIES_STRING_ARRAY =
            "vvm_disabled_capabilities_string_array";
    static final String KEY_VVM_CLIENT_PREFIX_STRING =
            "vvm_client_prefix_string";

    private final Context mContext;
    private final int mSubId;
    private final PersistableBundle mCarrierConfig;
    private final String mVvmType;

    private final PersistableBundle mTelephonyConfig;

    public OmtpVvmCarrierConfigHelper(Context context, int subId) {
        mContext = context;
        mSubId = subId;
        mCarrierConfig = getCarrierConfig();

        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mTelephonyConfig = new TelephonyVvmConfigManager(context.getResources())
                .getConfig(telephonyManager.getNetworkOperator(subId));

        mVvmType = getVvmType();
    }

    @VisibleForTesting
    OmtpVvmCarrierConfigHelper(PersistableBundle carrierConfig,
            PersistableBundle telephonyConfig) {
        mContext = null;
        mSubId = 0;
        mCarrierConfig = carrierConfig;
        mTelephonyConfig = telephonyConfig;
        mVvmType = getVvmType();
    }

    @Nullable
    public String getVvmType() {
        return (String) getValue(KEY_VVM_TYPE_STRING);
    }

    @Nullable
    public Set<String> getCarrierVvmPackageNames() {
        Set<String> names = getCarrierVvmPackageNames(mCarrierConfig);
        if (names != null) {
            return names;
        }
        return getCarrierVvmPackageNames(mTelephonyConfig);
    }

    private static Set<String> getCarrierVvmPackageNames(@Nullable PersistableBundle bundle) {
        if (bundle == null) {
            return null;
        }
        Set<String> names = new ArraySet<>();
        if (bundle.containsKey(KEY_CARRIER_VVM_PACKAGE_NAME_STRING)) {
            names.add(bundle.getString(KEY_CARRIER_VVM_PACKAGE_NAME_STRING));
        }
        if (bundle.containsKey(KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY)) {
            names.addAll(Arrays.asList(
                    bundle.getStringArray(KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY)));
        }
        if (names.isEmpty()) {
            return null;
        }
        return names;
    }

    public boolean isOmtpVvmType() {
        return (TelephonyManager.VVM_TYPE_OMTP.equals(mVvmType) ||
                TelephonyManager.VVM_TYPE_CVVM.equals(mVvmType));
    }

    /**
     * For checking upon sim insertion whether visual voicemail should be enabled. This method does
     * so by checking if the carrier's voicemail app is installed.
     */
    public boolean isEnabledByDefault() {
        Set<String> carrierPackages = getCarrierVvmPackageNames();
        if (carrierPackages == null) {
            return true;
        }
        for (String packageName : carrierPackages) {
            try {
                mContext.getPackageManager().getPackageInfo(packageName, 0);
                return false;
            } catch (NameNotFoundException e) {
                // Do nothing.
            }
        }
        return true;
    }

    public boolean isCellularDataRequired() {
        return (boolean) getValue(KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL);
    }

    public boolean isPrefetchEnabled() {
        return (boolean) getValue(KEY_VVM_PREFETCH_BOOL);
    }


    public int getApplicationPort() {
        Integer port = (Integer) getValue(KEY_VVM_PORT_NUMBER_INT);
        if (port != null) {
            return port;
        }
        return 0;
    }

    @Nullable
    public String getDestinationNumber() {
        return (String) getValue(KEY_VVM_DESTINATION_NUMBER_STRING);
    }

    /**
     * Hidden config.
     *
     * @return Port to start a SSL IMAP connection directly.
     *
     * TODO: make config public and add to CarrierConfigManager
     */
    @VisibleForTesting // TODO: remove after method used.
    public int getSslPort() {
        Integer port = (Integer) getValue(KEY_VVM_SSL_PORT_NUMBER_INT);
        if (port != null) {
            return port;
        }
        return 0;
    }

    /**
     * Hidden Config.
     *
     * @return A set of capabilities that is reported by the IMAP CAPABILITY command, but determined
     * to have issues and should not be used.
     */
    @VisibleForTesting // TODO: remove after method used.
    @Nullable
    public Set<String> getDisabledCapabilities() {
        Set<String> disabledCapabilities = getDisabledCapabilities(mCarrierConfig);
        if (disabledCapabilities != null) {
            return disabledCapabilities;
        }
        return getDisabledCapabilities(mTelephonyConfig);
    }

    @Nullable
    private static Set<String> getDisabledCapabilities(@Nullable PersistableBundle bundle) {
        if (bundle == null) {
            return null;
        }
        if (!bundle.containsKey(KEY_VVM_DISABLED_CAPABILITIES_STRING_ARRAY)) {
            return null;
        }
        ArraySet<String> result = new ArraySet<String>();
        result.addAll(
                Arrays.asList(bundle.getStringArray(KEY_VVM_DISABLED_CAPABILITIES_STRING_ARRAY)));
        return result;
    }

    public String getClientPrefix() {
        String prefix = (String) getValue(KEY_VVM_CLIENT_PREFIX_STRING);
        if (prefix != null) {
            return prefix;
        }
        return "//VVM";
    }

    public void startActivation() {
        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
        telephonyManager.setVisualVoicemailSmsFilterEnabled(mSubId, true);
        telephonyManager.setVisualVoicemailSmsFilterClientPrefix(mSubId, getClientPrefix());

        OmtpMessageSender messageSender = getMessageSender();
        if (messageSender != null) {
            Log.i(TAG, "Requesting VVM activation for subId: " + mSubId);
            messageSender.requestVvmActivation(null);
        }
    }

    public void startDeactivation() {
        mContext.getSystemService(TelephonyManager.class)
                .setVisualVoicemailSmsFilterEnabled(mSubId, false);
        OmtpMessageSender messageSender = getMessageSender();
        if (messageSender != null) {
            Log.i(TAG, "Requesting VVM deactivation for subId: " + mSubId);
            messageSender.requestVvmDeactivation(null);
        }
    }

    @Nullable
    private PersistableBundle getCarrierConfig() {
        if (!SubscriptionManager.isValidSubscriptionId(mSubId)) {
            Log.w(TAG, "Invalid subscriptionId or subscriptionId not provided in intent.");
            return null;
        }

        CarrierConfigManager carrierConfigManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager == null) {
            Log.w(TAG, "No carrier config service found.");
            return null;
        }

        PersistableBundle config = carrierConfigManager.getConfigForSubId(mSubId);

        if (TextUtils.isEmpty(config.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING))) {
            Log.w(TAG, "Carrier config missing VVM type, ignoring.");
            return null;
        }
        return config;
    }

    private OmtpMessageSender getMessageSender() {
        if (mCarrierConfig == null && mTelephonyConfig == null) {
            Log.w(TAG, "Empty carrier config.");
            return null;
        }

        int applicationPort = getApplicationPort();
        String destinationNumber = getDestinationNumber();
        if (TextUtils.isEmpty(destinationNumber)) {
            Log.w(TAG, "No destination number for this carrier.");
            return null;
        }

        OmtpMessageSender messageSender = null;
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(mSubId);
        switch (mVvmType) {
            case TelephonyManager.VVM_TYPE_OMTP:
                messageSender = new OmtpStandardMessageSender(smsManager, (short) applicationPort,
                        destinationNumber, null, OmtpConstants.PROTOCOL_VERSION1_1, null);
                break;
            case TelephonyManager.VVM_TYPE_CVVM:
                messageSender = new OmtpCvvmMessageSender(smsManager, (short) applicationPort,
                        destinationNumber);
                break;
            default:
                Log.w(TAG, "Unexpected visual voicemail type: " + mVvmType);
        }

        return messageSender;
    }

    @Nullable
    private Object getValue(String key) {
        Object result;
        if (mCarrierConfig != null) {
            result = mCarrierConfig.get(key);
            if (result != null) {
                return result;
            }
        }
        if (mTelephonyConfig != null) {
            result = mTelephonyConfig.get(key);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}