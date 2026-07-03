/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.adbye.filter.activities;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.adbye.filter.Log;
import com.adbye.filter.R;
import com.adbye.filter.Utils;
import com.adbye.filter.fragments.EditListFragment;
import com.adbye.filter.fragments.FirewallStatus;
import com.adbye.filter.fragments.ProtectionFragment;
import com.adbye.filter.model.ListInfo;
import com.adbye.filter.model.Prefs;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class FirewallActivity extends BaseActivity {
    private static final String TAG = "Firewall";
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;
    private boolean mHasWhitelist = false;
    private SharedPreferences mPrefs;

    private static final int POS_STATUS = 0;
    private static final int POS_BLOCKLIST = 1;
    private static final int POS_WHITELIST = 2;
    private static final int POS_PROTECTION = 3;
    private static final int BASE_TOTAL_COUNT = 3;
    private static final int TOTAL_COUNT = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.firewall);
        setContentView(R.layout.tabs_activity);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPager = findViewById(R.id.pager);
        Utils.fixViewPager2Insets(mPager);
        setupTabs();
    }

    private class StateAdapter extends FragmentStateAdapter {
        StateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "createFragment");

            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return new FirewallStatus();
                case POS_BLOCKLIST:
                    return EditListFragment.newInstance(ListInfo.Type.BLOCKLIST);
                case POS_WHITELIST:
                    return EditListFragment.newInstance(ListInfo.Type.FIREWALL_WHITELIST);
                case POS_PROTECTION:
                    ProtectionFragment pf = new ProtectionFragment();
                    pf.setCallback(FirewallActivity.this::onProtectionChanged);
                    return pf;
            }
        }

        @Override
        public int getItemCount() {
            // Protection tab is always present; whitelist only when whitelist mode is on.
            return mHasWhitelist ? TOTAL_COUNT : (TOTAL_COUNT - 1);
        }

        public int getPageTitle(final int position) {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return R.string.status;
                case POS_BLOCKLIST:
                    return R.string.blocklist;
                case POS_WHITELIST:
                    return R.string.whitelist;
                case POS_PROTECTION:
                    return R.string.adbye_protection_tab;
            }
        }
    }

    /**
     * Toggled by {@link ProtectionFragment.Callback}; for now we just log.
     * Engine hooks (FilterListManager.mergeEnabledLists / CaptureService reloads) are
     * wired in the next phase â€” see plan.md Phase 1 â†’ Phase 4 handoff.
     */
    private void onProtectionChanged(String prefKey, boolean enabled) {
        Log.d(TAG, "Protection changed: " + prefKey + "=" + enabled);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void recheckTabs() {
        boolean whitelist_mode = Prefs.isFirewallWhitelistMode(mPrefs);
        if(mHasWhitelist == whitelist_mode)
            return;

        mHasWhitelist = whitelist_mode;
        mPagerAdapter.notifyDataSetChanged();
    }

    private void setupTabs() {
        mPagerAdapter = new StateAdapter(this);
        mPager.setAdapter(mPagerAdapter);

        // Keep all the tabs attached. Otherwise, navigating to a non-adjacent tab detaches an
        // edit list page from the window, which breaks the ListView CHOICE_MODE_MULTIPLE_MODAL
        // long-press selection when the page is shown again (e.g. the whitelist tab).
        mPager.setOffscreenPageLimit(TOTAL_COUNT - 1);

        new TabLayoutMediator(findViewById(R.id.tablayout), mPager, (tab, position) ->
                tab.setText(getString(mPagerAdapter.getPageTitle(position)))
        ).attach();

        recheckTabs();

        // TODO fix DPAD navigation on Android TV, see MainActivity.onKeyDown
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // This is required to properly handle the DPAD down press on Android TV, to properly
        // focus the tab content
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View view = getCurrentFocus();

            Log.d(TAG, "onKeyDown focus " + view.getClass().getName());

            if (view instanceof TabLayout.TabView) {
                int pos = mPager.getCurrentItem();
                View focusOverride = null;

                Log.d(TAG, "TabLayout.TabView focus pos " + pos);

                if (pos == POS_STATUS)
                    focusOverride = findViewById(R.id.firewall_status);
                else if ((pos == POS_BLOCKLIST) || (pos == POS_WHITELIST))
                    focusOverride = findViewById(R.id.listview);
                else if (pos == POS_PROTECTION)
                    focusOverride = findViewById(R.id.protection_list);

                if (focusOverride != null) {
                    focusOverride.requestFocus();
                    return true;
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }
}


