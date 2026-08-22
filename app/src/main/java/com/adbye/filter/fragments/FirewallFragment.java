/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Firewall tab (F3, 2026-08-13). Reuses the existing (upstream) Blocklist/
 * Whitelist editors via EditListActivity -- does NOT rebuild EditListFragment,
 * just navigates to it. The master toggle mirrors FirewallStatus.java's
 * existing toolbar switch wiring exactly (Prefs.isFirewallEnabled /
 * Prefs.PREF_FIREWALL / CaptureService.setFirewallEnabled) -- zero new native
 * code, same pattern already live elsewhere in the app. The placeholder text
 * for granular network-state (Wi-Fi/Mobile/roaming/screen-off) rules is TEXT
 * ONLY, not a fake-functional toggle -- plan.md marks that feature NOT
 * authorized / NOT scheduled as of 2026-07-23.
 */
package com.adbye.filter.fragments;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.adbye.filter.CaptureService;
import com.adbye.filter.Log;
import com.adbye.filter.R;
import com.adbye.filter.activities.EditListActivity;
import com.adbye.filter.model.ListInfo;
import com.adbye.filter.model.Prefs;
import com.google.android.material.materialswitch.MaterialSwitch;

public class FirewallFragment extends Fragment {
    private static final String TAG = "FirewallFragment";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_firewall, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView title = view.findViewById(R.id.row_title);
        TextView subtitle = view.findViewById(R.id.row_subtitle);
        MaterialSwitch sw = view.findViewById(R.id.row_switch);

        title.setText(R.string.adbye_protect_firewall_title);
        subtitle.setText(R.string.adbye_protect_firewall_subtitle);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean enabled = Prefs.isFirewallEnabled(requireContext(), prefs);

        sw.setOnCheckedChangeListener(null);
        sw.setChecked(enabled);
        sw.setOnCheckedChangeListener((v, isChecked) -> {
            if (isChecked == Prefs.isFirewallEnabled(requireContext(), prefs))
                return; // not changed

            Log.d(TAG, "Firewall is now " + (isChecked ? "enabled" : "disabled"));
            CaptureService.setFirewallEnabled(isChecked);
            prefs.edit().putBoolean(Prefs.PREF_FIREWALL, isChecked).apply();
        });

        view.findViewById(R.id.blocklist_row).setOnClickListener(
                v -> openEditList(ListInfo.Type.BLOCKLIST));
        view.findViewById(R.id.whitelist_row).setOnClickListener(
                v -> openEditList(ListInfo.Type.FIREWALL_WHITELIST));

        TextView placeholder = view.findViewById(R.id.firewall_stub_message);
        placeholder.setText(R.string.adbye_firewall_stub_msg);
    }

    private void openEditList(ListInfo.Type type) {
        Intent intent = new Intent(requireContext(), EditListActivity.class);
        intent.putExtra(EditListActivity.LIST_TYPE_EXTRA, type);
        startActivity(intent);
    }
}
