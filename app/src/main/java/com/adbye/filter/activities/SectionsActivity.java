/*
 * This file is part of ADBye (PCAPdroid).
 *
 * New top-level navigation shell (2026-08-11 nav-restructure, Approach 2 per the
 * scoping report). 5 swipeable ViewPager2 tabs (Protection / Network / App /
 * Firewall / Filters & User Rules) + a toolbar gear icon that deep-links to the
 * existing SettingsActivity for Preferences (lead decision D3).
 *
 * FirewallActivity (Status/Blocklist/Protection/Whitelist/AppManagement) is left
 * COMPLETELY untouched -- still reachable from the existing drawer "Firewall"
 * entry and from the CaptureService notification, so FirewallStatus keeps its
 * one working home. This activity is reached via a NEW, separate drawer entry.
 *
 * Network / Firewall / Filters & User Rules tabs are stubs in this shell commit --
 * real content lands in dedicated follow-up commits.
 */
package com.adbye.filter.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.adbye.filter.R;
import com.adbye.filter.Utils;
import com.adbye.filter.activities.prefs.SettingsActivity;
import com.adbye.filter.fragments.AppManagementFragment;
import com.adbye.filter.fragments.ProtectionFragment;
import com.adbye.filter.fragments.NetworkFragment;
import com.adbye.filter.fragments.FirewallFragment;
import com.adbye.filter.fragments.SectionStubFragment;
import com.google.android.material.tabs.TabLayoutMediator;

public class SectionsActivity extends BaseActivity {
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;

    private static final int POS_PROTECTION = 0;
    private static final int POS_NETWORK = 1;
    private static final int POS_APP = 2;
    private static final int POS_FIREWALL = 3;
    private static final int POS_FILTERS = 4;
    private static final int TOTAL_COUNT = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.adbye_sections_title);
        setContentView(R.layout.tabs_activity);
        mPager = findViewById(R.id.pager);
        Utils.fixViewPager2Insets(mPager);
        setupTabs();
    }

    private class StateAdapter extends FragmentStateAdapter {
        StateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                default:
                case POS_PROTECTION:
                    return new ProtectionFragment();
                case POS_NETWORK:
                    return new NetworkFragment();
                case POS_APP:
                    return new AppManagementFragment();
                case POS_FIREWALL:
                    return new FirewallFragment();
                case POS_FILTERS:
                    return SectionStubFragment.newInstance(getString(R.string.adbye_filters_stub_msg));
            }
        }

        @Override
        public int getItemCount() {
            return TOTAL_COUNT;
        }

        public int getPageTitle(final int position) {
            switch (position) {
                default:
                case POS_PROTECTION:
                    return R.string.adbye_protection_tab;
                case POS_NETWORK:
                    return R.string.adbye_network_tab;
                case POS_APP:
                    return R.string.apps;
                case POS_FIREWALL:
                    return R.string.firewall;
                case POS_FILTERS:
                    return R.string.adbye_filters_tab;
            }
        }
    }

    private void setupTabs() {
        mPagerAdapter = new StateAdapter(this);
        mPager.setAdapter(mPagerAdapter);
        mPager.setOffscreenPageLimit(TOTAL_COUNT - 1);
        new TabLayoutMediator(findViewById(R.id.tablayout), mPager, (tab, position) ->
                tab.setText(getString(mPagerAdapter.getPageTitle(position)))
        ).attach();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.sections_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.open_preferences) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
