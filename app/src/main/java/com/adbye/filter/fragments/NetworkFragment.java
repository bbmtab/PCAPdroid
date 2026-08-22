/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Network tab. Currently hosts just the "HTTPS Filtering" master switch --
 * lead decision D1 branch (a-ii): this is a UI relocation of the row that
 * used to live in ProtectionFragment as "Ad blocking", not a new feature.
 * Same pref key (Prefs.PREF_PROTECT_ADBLOCK) and same native backend
 * (CaptureService.setAdblockEnabled / pd->adblock.enabled) as before -- zero
 * new native code, per the 2026-08-11 nav-restructure scoping report's
 * "load-bearing finding 2". Reload logic is shared with ProtectionFragment
 * via ProtectionHotReload.
 */
package com.adbye.filter.fragments;

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

import com.adbye.filter.R;
import com.adbye.filter.model.Prefs;
import com.google.android.material.materialswitch.MaterialSwitch;

public class NetworkFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView title = view.findViewById(R.id.row_title);
        TextView subtitle = view.findViewById(R.id.row_subtitle);
        MaterialSwitch sw = view.findViewById(R.id.row_switch);

        title.setText(R.string.adbye_network_https_title);
        subtitle.setText(R.string.adbye_network_https_subtitle);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean enabled = Prefs.isProtectAdblock(prefs);

        sw.setOnCheckedChangeListener(null);
        sw.setChecked(enabled);
        sw.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean(Prefs.PREF_PROTECT_ADBLOCK, isChecked).apply();
            ProtectionHotReload.apply(requireContext().getApplicationContext(),
                    Prefs.PREF_PROTECT_ADBLOCK, isChecked);
        });
        view.setOnClickListener(v -> sw.toggle());
    }
}
