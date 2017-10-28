/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.R;
import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNetworkSelector}.
 */
@SmallTest
public class WifiNetworkSelectorTest {

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setupContext();
        setupResources();
        setupWifiConfigManager();
        setupWifiInfo();
        mLocalLog = new LocalLog(512);

        mWifiNetworkSelector = new WifiNetworkSelector(mContext, mWifiConfigManager, mClock,
                mLocalLog);
        mWifiNetworkSelector.registerNetworkEvaluator(mDummyEvaluator, 1);
        mDummyEvaluator.setEvaluatorToSelectCandidate(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());

        mThresholdMinimumRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdMinimumRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdQualifiedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdSaturatedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mThresholdSaturatedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * All this dummy network evaluator does is to pick the very first network
     * in the scan results.
     */
    public class DummyNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
        private static final String NAME = "DummyNetworkEvaluator";

        private boolean mEvaluatorShouldSelectCandidate = true;

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public void update(List<ScanDetail> scanDetails) {}

        /**
         * Sets whether the evaluator should return a candidate for connection or null.
         */
        public void setEvaluatorToSelectCandidate(boolean shouldSelectCandidate) {
            mEvaluatorShouldSelectCandidate = shouldSelectCandidate;
        }

        /**
         * This NetworkEvaluator can be configured to return a candidate or null.  If returning a
         * candidate, the first entry in the provided scanDetails will be selected. This requires
         * that the mock WifiConfigManager be set up to return a WifiConfiguration for the first
         * scanDetail entry, through
         * {@link WifiNetworkSelectorTestUtil#setupScanDetailsAndConfigStore}.
         */
        @Override
        public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
            if (!mEvaluatorShouldSelectCandidate) {
                return null;
            }
            ScanDetail scanDetail = scanDetails.get(0);
            mWifiConfigManager.setNetworkCandidateScanResult(0, scanDetail.getScanResult(), 100);

            assertNotNull("Saved network must not be null",
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail));

            return mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);
        }
    }

    private WifiNetworkSelector mWifiNetworkSelector = null;
    private DummyNetworkEvaluator mDummyEvaluator = new DummyNetworkEvaluator();
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private Context mContext;
    @Mock private Resources mResource;
    @Mock private WifiInfo mWifiInfo;
    @Mock private Clock mClock;
    private LocalLog mLocalLog;
    private int mThresholdMinimumRssi2G;
    private int mThresholdMinimumRssi5G;
    private int mThresholdQualifiedRssi2G;
    private int mThresholdQualifiedRssi5G;
    private int mThresholdSaturatedRssi2G;
    private int mThresholdSaturatedRssi5G;

    private void setupContext() {
        when(mContext.getResources()).thenReturn(mResource);
    }

    private void setupResources() {
        when(mResource.getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection)).thenReturn(true);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
                .thenReturn(-73);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz))
                .thenReturn(-82);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz))
                .thenReturn(-85);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz))
                .thenReturn(-57);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz))
                .thenReturn(-60);
    }

    private void setupWifiInfo() {
        // simulate a disconnected state
        when(mWifiInfo.is24GHz()).thenReturn(true);
        when(mWifiInfo.is5GHz()).thenReturn(false);
        when(mWifiInfo.getRssi()).thenReturn(-70);
        when(mWifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(mWifiInfo.getBSSID()).thenReturn(null);
    }

    private void setupWifiConfigManager() {
        when(mWifiConfigManager.getLastSelectedNetwork())
                .thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
    }

    /**
     * No network selection if scan result is empty.
     *
     * WifiStateMachine is in disconnected state.
     * scanDetails is empty.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void emptyScanResults() {
        String[] ssids = new String[0];
        String[] bssids = new String[0];
        int[] freqs = new int[0];
        String[] caps = new String[0];
        int[] levels = new int[0];
        int[] securities = new int[0];

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }


    /**
     * No network selection if the RSSI values in scan result are too low.
     *
     * WifiStateMachine is in disconnected state.
     * scanDetails contains a 2.4GHz and a 5GHz network, but both with RSSI lower than
     * the threshold
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void verifyMinimumRssiThreshold() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G - 1, mThresholdMinimumRssi5G - 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * No network selection if WiFi is connected and it is too short from last
     * network selection.
     *
     * WifiStateMachine is in connected state.
     * scanDetails contains two valid networks.
     * Perform a network seletion right after the first one.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void verifyMinimumTimeGapWhenConnected() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS - 2000);

        // Do another network selection with WSM in CONNECTED state.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * Perform network selection if WiFi is disconnected even if it is too short from last
     * network selection.
     *
     * WifiStateMachine is in disconnected state.
     * scanDetails contains two valid networks.
     * Perform a network seletion right after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void verifyNoMinimumTimeGapWhenDisconnected() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS - 2000);

        // Do another network selection with WSM in DISCONNECTED state.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * New network selection is performed if the currently connected network
     * is a open one.
     *
     * WifiStateMachine is connected to a open network.
     * scanDetails contains a valid networks.
     * Perform a network seletion after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void openNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_NONE};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);
        when(mWifiInfo.is5GHz()).thenReturn(true);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * New network selection is performed if the currently connected network
     * has low RSSI value.
     *
     * WifiStateMachine is connected to a low RSSI 5GHz network.
     * scanDetails contains a valid networks.
     * Perform a network seletion after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void lowRssi5GNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G - 2};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);
        when(mWifiInfo.is5GHz()).thenReturn(true);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Blacklisted BSSID is filtered out for network selection.
     *
     * WifiStateMachine is disconnected.
     * scanDetails contains a network which is blacklisted.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void filterOutBlacklistedBssid() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        blacklist.add(bssids[0]);

        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * Wifi network selector doesn't recommend any network if the currently connected one
     * doesn't show up in the scan results.
     *
     * WifiStateMachine is under connected state and 2.4GHz test1 is connected.
     * The second scan results contains only test2 which now has a stronger RSSI than test1.
     * Test1 is not in the second scan results.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void noSelectionWhenCurrentNetworkNotInScanResults() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 2457};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 20, mThresholdMinimumRssi2G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection to connect to test1.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(true);
        when(mWifiInfo.is5GHz()).thenReturn(false);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Prepare the second scan results which have no test1.
        String[] ssidsNew = {"\"test2\""};
        String[] bssidsNew = {"6c:f3:7f:ae:8c:f4"};
        int[] freqsNew = {2457};
        String[] capsNew = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levelsNew = {mThresholdMinimumRssi2G + 40};
        scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(ssidsNew, bssidsNew,
                freqsNew, capsNew, levelsNew, mClock);
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo,
                true, false, false);

        // The second network selection is skipped since current connected network is
        // missing from the scan results.
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * Ensures that settings the user connect choice updates the
     * NetworkSelectionStatus#mConnectChoice for all other WifiConfigurations in range in the last
     * round of network selection.
     *
     * Expected behavior: WifiConfiguration.NetworkSelectionStatus#mConnectChoice is set to
     *                    test1's configkey for test2. test3's WifiConfiguration is unchanged.
     */
    @Test
    public void setUserConnectChoice() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5"};
        int[] freqs = {2437, 5180, 5181};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1,
                mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);

        WifiConfiguration selectedWifiConfig = scanDetailsAndConfigs.getWifiConfigs()[0];
        selectedWifiConfig.getNetworkSelectionStatus()
                .setCandidate(scanDetailsAndConfigs.getScanDetails().get(0).getScanResult());
        selectedWifiConfig.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        selectedWifiConfig.getNetworkSelectionStatus().setConnectChoice("bogusKey");

        WifiConfiguration configInLastNetworkSelection = scanDetailsAndConfigs.getWifiConfigs()[1];
        configInLastNetworkSelection.getNetworkSelectionStatus()
                .setSeenInLastQualifiedNetworkSelection(true);

        WifiConfiguration configNotInLastNetworkSelection =
                scanDetailsAndConfigs.getWifiConfigs()[2];

        assertTrue(mWifiNetworkSelector.setUserConnectChoice(selectedWifiConfig.networkId));

        verify(mWifiConfigManager).updateNetworkSelectionStatus(selectedWifiConfig.networkId,
                NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        verify(mWifiConfigManager).clearNetworkConnectChoice(selectedWifiConfig.networkId);
        verify(mWifiConfigManager).setNetworkConnectChoice(configInLastNetworkSelection.networkId,
                selectedWifiConfig.configKey(), mClock.getWallClockMillis());
        verify(mWifiConfigManager, never()).setNetworkConnectChoice(
                configNotInLastNetworkSelection.networkId, selectedWifiConfig.configKey(),
                mClock.getWallClockMillis());
    }

    /**
     * If two qualified networks, test1 and test2, are in range when the user selects test2 over
     * test1, WifiNetworkSelector will override the NetworkSelector's choice to connect to test1
     * with test2.
     *
     * Expected behavior: test2 is the recommended network
     */
    @Test
    public void userConnectChoiceOverridesNetworkEvaluators() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();

        // DummyEvaluator always selects the first network in the list.
        WifiConfiguration networkSelectorChoice = scanDetailsAndConfigs.getWifiConfigs()[0];
        networkSelectorChoice.getNetworkSelectionStatus()
                .setSeenInLastQualifiedNetworkSelection(true);

        WifiConfiguration userChoice = scanDetailsAndConfigs.getWifiConfigs()[1];
        userChoice.getNetworkSelectionStatus()
                .setCandidate(scanDetailsAndConfigs.getScanDetails().get(1).getScanResult());

        // With no user choice set, networkSelectorChoice should be chosen.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        WifiConfigurationTestUtil.assertConfigurationEqual(networkSelectorChoice, candidate);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        assertTrue(mWifiNetworkSelector.setUserConnectChoice(userChoice.networkId));

        // After user connect choice is set, userChoice should override networkSelectorChoice.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        WifiConfigurationTestUtil.assertConfigurationEqual(userChoice, candidate);
    }

    /**
     * Wifi network selector doesn't recommend any network if the currently connected 2.4Ghz
     * network is high quality and no 5GHz networks are available
     *
     * WifiStateMachine is under connected state and 2.4GHz test1 is connected.
     *
     * Expected behavior: no network selection is performed
     */
    @Test
    public void test2GhzQualifiedNo5GhzAvailable() {
        // Do not perform selection on 2GHz if current network is good and no 5GHz available
        testStayOrSwitch(mThresholdQualifiedRssi2G, false,
                mThresholdQualifiedRssi2G + 10, false, false);
    }

    /**
     * Wifi network selector performs network selection even when the 2Ghz network is high
     * quality whenever 5Ghz networks are available.
     *
     * WifiStateMachine is under connected state and 2.4GHz test1 is connected.
     * The scan results contain a 5Ghz network, which forces network selection.
     * Test1 is not in the second scan results.
     *
     * Expected behavior: network selection is performed
     */
    @Test
    public void test2GhzHighQuality5GhzAvailable() {
        // When on 2GHz, even with "good" signal strength, run selection if 5GHz available
        testStayOrSwitch(mThresholdQualifiedRssi2G, false,
                mThresholdQualifiedRssi5G - 1, true, true);
    }

    /**
     * Wifi network selector performs network selection on 2Ghz networks if the current network
     * is not of high quality.
     *
     * WifiStateMachine is under connected state and 2.4GHz test1 is connected.
     *
     * Expected behavior: network selection is performed
     */
    @Test
    public void test2GhzNotQualifiedOther2GhzAvailable() {
        // Run Selection on 2Ghz networks when not qualified even if no 5GHz available
        testStayOrSwitch(mThresholdQualifiedRssi2G - 1, false,
                mThresholdQualifiedRssi2G - 10, false, true);
    }

    /**
     * Wifi network selector performs network selection when connected to a 5Ghz network that
     * has an insufficient RSSI.
     *
     * WifiStateMachine is under connected state and 5GHz test1 is connected.
     *
     * Expected behavior: network selection is performed
     */
    @Test
    public void test5GhzNotQualifiedLowRssi() {
        // Run Selection when the current 5Ghz network has low RSSI, regardless of what may
        // be available. The second scan result is irrelevant.
        testStayOrSwitch(mThresholdQualifiedRssi5G - 1, true,
                mThresholdQualifiedRssi2G - 1, false, true);
    }

    /**
     * Wifi network selector will not run selection when on a 5Ghz network that is of sufficent
     * Quality (high-enough RSSI).
     *
     * WifiStateMachine is under connected state and 5GHz test1 is connected.
     *
     * Expected behavior: network selection is not performed
     */
    @Test
    public void test5GhzQualified() {
        // Connected to a high quality 5Ghz network, so the other result is irrelevant
        testStayOrSwitch(mThresholdQualifiedRssi5G, true,
                mThresholdQualifiedRssi5G + 10, true, false);
    }

    /**
     * This is a meta-test that given two scan results of various types, will
     * determine whether or not network selection should be performed.
     *
     * It sets up two networks, connects to the first, and then ensures that
     * both are available in the scan results for the NetworkSelector.
     */
    private void testStayOrSwitch(int levelNetwork1, boolean is5GHzNetwork1,
                int levelNetwork2, boolean is5GHzNetwork2, boolean shouldSelect) {
        // Create an updated ScanDetails that includes a new network
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = new int[2];
        freqs[0] = is5GHzNetwork1 ? 5180 : 2437;
        freqs[1] = is5GHzNetwork2 ? 5180 : 2437;
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {levelNetwork1, levelNetwork2};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection to connect to test1.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(!is5GHzNetwork1);
        when(mWifiInfo.is5GHz()).thenReturn(is5GHzNetwork1);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        candidate = mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo,
                true, false, false);

        if (!shouldSelect) {
            assertEquals("Expect null configuration", null, candidate);
        } else {
            assertNotNull("Result should be not null", candidate);
            ScanResult expectedResult = scanDetails.get(0).getScanResult();
            WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                    expectedResult, candidate);
        }
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should filter out
     * networks that are not open after network selection is made.
     *
     * Expected behavior: return open networks only
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersForOpenNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        List<ScanDetail> expectedOpenUnsavedNetworks = new ArrayList<>();
        expectedOpenUnsavedNetworks.add(scanDetails.get(1));
        assertEquals("Expect open unsaved networks",
                expectedOpenUnsavedNetworks,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should filter out
     * saved networks after network selection is made. This should return an empty list when there
     * are no unsaved networks available.
     *
     * Expected behavior: return unsaved networks only. Return empty list if there are no unsaved
     * networks.
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersOutSavedNetworks() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1};
        int[] securities = {SECURITY_NONE};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> unSavedScanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(
                unSavedScanDetails, blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect open unsaved networks",
                unSavedScanDetails,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> savedScanDetails = scanDetailsAndConfigs.getScanDetails();

        mWifiNetworkSelector.selectNetwork(
                savedScanDetails, blacklist, mWifiInfo, false, true, false);
        // Saved networks are filtered out.
        assertTrue(mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks().isEmpty());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should filter out
     * bssid blacklisted networks.
     *
     * Expected behavior: do not return blacklisted network
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersOutBlacklistedNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();
        blacklist.add(bssids[0]);

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        List<ScanDetail> expectedOpenUnsavedNetworks = new ArrayList<>();
        expectedOpenUnsavedNetworks.add(scanDetails.get(1));
        assertEquals("Expect open unsaved networks",
                expectedOpenUnsavedNetworks,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should return
     * empty list when there are no open networks after network selection is made.
     *
     * Expected behavior: return empty list
     */
    @Test
    public void getfilterOpenUnsavedNetworks_returnsEmptyListWhenNoOpenNetworksPresent() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        assertTrue(mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks().isEmpty());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should return
     * empty list when no network selection has been made.
     *
     * Expected behavior: return empty list
     */
    @Test
    public void getfilterOpenUnsavedNetworks_returnsEmptyListWhenNoNetworkSelectionMade() {
        assertTrue(mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks().isEmpty());
    }
}

