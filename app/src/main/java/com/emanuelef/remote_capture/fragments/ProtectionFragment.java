/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Protection tab — AdGuard-style master switches (6 toggles).
 * See firewall_blocking_tab.md.
 */
package com.emanuelef.remote_capture.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts the 6 master toggles:
 *   Ad blocking / Tracking protection / Annoyance blocking /
 *   DNS protection / Firewall / Browsing security.
 *
 * Each row writes to its pref key in {@link Prefs} via a {@link Callback}.
 * The actual engine reload happens via the consumer that the host wires in
 * (see Phase 1 in plan.md).
 */
public class ProtectionFragment extends Fragment {

    public interface Callback {
        void onProtectionChanged(@NonNull String prefKey, boolean enabled);
    }

    private static class Row {
        final String prefKey;
        final boolean defaultOn;
        final int titleRes;
        final int subtitleRes;
        boolean enabled;

        Row(String key, boolean def, int title, int subtitle) {
            this.prefKey = key;
            this.defaultOn = def;
            this.titleRes = title;
            this.subtitleRes = subtitle;
        }
    }

    private final List<Row> mRows = new ArrayList<>();
    private RecyclerView mRecycler;
    private Callback mCallback;

    /** Set a callback that receives toggled pref keys. Called from host Activity. */
    public void setCallback(Callback cb) {
        mCallback = cb;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRows.clear();
        mRows.add(new Row(Prefs.PREF_PROTECT_ADBLOCK,   true,  R.string.adbye_protect_adblock_title,   R.string.adbye_protect_adblock_subtitle));
        mRows.add(new Row(Prefs.PREF_PROTECT_TRACKING,  true,  R.string.adbye_protect_tracking_title,  R.string.adbye_protect_tracking_subtitle));
        mRows.add(new Row(Prefs.PREF_PROTECT_ANNOYANCE, true,  R.string.adbye_protect_annoyance_title, R.string.adbye_protect_annoyance_subtitle));
        mRows.add(new Row(Prefs.PREF_PROTECT_DNS,       true,  R.string.adbye_protect_dns_title,       R.string.adbye_protect_dns_subtitle));
        mRows.add(new Row(Prefs.PREF_PROTECT_FIREWALL,  true,  R.string.adbye_protect_firewall_title,  R.string.adbye_protect_firewall_subtitle));
        mRows.add(new Row(Prefs.PREF_PROTECT_SECURITY,  true,  R.string.adbye_protect_security_title,  R.string.adbye_protect_security_subtitle));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_protection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        // Hydrate current state.
        for (Row r : mRows) {
            r.enabled = prefs.getBoolean(r.prefKey, r.defaultOn);
        }
        mRecycler = view.findViewById(R.id.protection_list);
        mRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecycler.setAdapter(new Adapter());
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.protection_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = mRows.get(position);
            h.title.setText(r.titleRes);
            h.subtitle.setText(r.subtitleRes);
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(r.enabled);
            h.sw.setOnCheckedChangeListener((view, isChecked) -> {
                r.enabled = isChecked;
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                prefs.edit().putBoolean(r.prefKey, isChecked).apply();
                if (mCallback != null) mCallback.onProtectionChanged(r.prefKey, isChecked);
            });
            h.itemView.setOnClickListener(v -> h.sw.toggle());
        }

        @Override public int getItemCount() { return mRows.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final android.widget.TextView title, subtitle;
            final MaterialSwitch sw;
            final View itemView;
            VH(@NonNull View v) {
                super(v);
                itemView = v;
                title = v.findViewById(R.id.row_title);
                subtitle = v.findViewById(R.id.row_subtitle);
                sw = v.findViewById(R.id.row_switch);
            }
        }
    }
}
