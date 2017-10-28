/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2017. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

package com.mediatek.location;

import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.location.GnssLocationProvider;
import com.android.server.LocationManagerService;

public class MtkLocationExt {
    private static final String TAG = "MtkMtkLocationExt";

    private static final boolean DEBUG = LocationManagerService.D;

    /// mtk added deleting aiding data flags
    private static final int GPS_DELETE_HOT_STILL = 0x2000;
    private static final int GPS_DELETE_EPO = 0x4000;

    private static final String VZW_DEBUG_MSG = "com.mediatek.location.debug_message";

    private static MtkGnssProviderExt sProviderExt;

    //============================================================================================
    // APIs for GnssLocationProvider
    public static MtkGnssProviderExt createGnssProviderExt(
            GnssLocationProvider gnssProvider,
            Context context,
            Handler gnssHandler) {
        if (null == sProviderExt && null != gnssProvider) {
            sProviderExt = new MtkGnssProviderExt(gnssProvider, context, gnssHandler);
            Log.d(TAG, "LocationExt MtkGnssProviderExt is created");
        }
        return sProviderExt;
    }

    public static boolean isProviderExtEnabled() {
        return (null != sProviderExt);
    }

    public static int deleteAidingData(Bundle extras, int flags) {
        if (!isProviderExtEnabled()) return flags;
        if (extras != null) {
            if (extras.getBoolean("hot-still")) flags |= GPS_DELETE_HOT_STILL;
            if (extras.getBoolean("epo")) flags |= GPS_DELETE_EPO;
        }
        Log.d(TAG, "deleteAidingData extras:" + extras + "flags:" + flags);
        return flags;
    }

    public static boolean checkExtraCommand(String command, Bundle extras) {
        if (!isProviderExtEnabled()) return false;
        return sProviderExt.checkExtraCommand(command, extras);
    }

    public static void reportVzwDebugMessage(String vzw_msg) {
        if (!isProviderExtEnabled()) return;
        sProviderExt.reportVzwDebugMessage(vzw_msg);
    }

    // APIs for LocationManagerService

    //============================================================================================
    // Internal implementation
    private static class MtkGnssProviderExt {

        private final GnssLocationProvider mGnssProvider;
        private final Context mContext;
        private final Handler mGnssHandler;

        public MtkGnssProviderExt(GnssLocationProvider gnssProvider, Context context,
                Handler gnssHandler) {
            mGnssProvider = gnssProvider;
            mContext = context;
            mGnssHandler = gnssHandler;
        }

        private boolean checkExtraCommand(String command, Bundle extras) {
            Log.d(TAG, "checkExtraCommand command= " + command);
            boolean result = false;
            if ("set_vzw_debug_screen".equals(command)) {
                boolean eanbled = (extras != null) ? extras.getBoolean("enabled") : false;
                mGnssProvider.setVzwDebugScreen(eanbled);
                result = true;
            }
            return result;
        }

        private void reportVzwDebugMessage(String vzw_msg) {
            if (DEBUG) Log.d(TAG, "reportVzwDebugMessage vzw_msg: " + vzw_msg);
            /// broadcast vzw_msg in intent here.
            Intent intent = new Intent(VZW_DEBUG_MSG);
            intent.putExtra("vzw_dbg", vzw_msg);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }
}
