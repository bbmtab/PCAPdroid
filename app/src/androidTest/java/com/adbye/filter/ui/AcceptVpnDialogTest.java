/*
 * This file is part of ADBye.
 *
 * ADBye is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ADBye is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ADBye.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2026 - Emanuele Faranda
 */
package com.adbye.filter.ui;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * UIAutomator test to accept the VPN permission dialog when the app starts.
 * This test is invoked from CI/CD via:
 *   adb shell uiautomator runtest -c com.adbye.filter.ui.AcceptVpnDialogTest ...
 *
 * The test launches MainActivity and clicks "OK" on the VPN permission dialog.
 */
@RunWith(AndroidJUnit4.class)
public class AcceptVpnDialogTest {
    private UiDevice device;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // Wake up the device if needed
        device.wakeUp();
    }

    @After
    public void tearDown() {
        // Press home to clean up
        device.pressHome();
    }

    /**
     * Waits for and accepts the VPN permission dialog.
     * The dialog appears when the app requests VPN permission via VpnService.prepare().
     */
    @Test
    public void acceptVpnDialog() {
        // Wait for the VPN permission dialog to appear
        // The dialog title is typically "Connection request" or similar
        // The OK button has text "OK" or "Allow" depending on Android version
        UiObject2 okButton = device.wait(Until.findObject(By.textContains("OK")), 30000);
        if (okButton == null) {
            // Try alternative button texts
            okButton = device.wait(Until.findObject(By.text("Allow")), 5000);
        }
        if (okButton == null) {
            okButton = device.wait(Until.findObject(By.text("I trust this application")), 5000);
        }
        if (okButton == null) {
            // Some devices show "OK" in a different case or with different ID
            okButton = device.findObject(By.res("android:id/button1"));
        }

        assertNotNull("VPN permission dialog OK/Allow button not found", okButton);
        assertTrue("Failed to click VPN permission OK button", okButton.click());

        // Wait a moment for the dialog to dismiss
        device.waitForIdle(2000);
    }
}