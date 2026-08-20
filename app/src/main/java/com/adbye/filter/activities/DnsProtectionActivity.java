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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.adbye.filter.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.R;
import com.adbye.filter.dns.DnsProvider;
import com.adbye.filter.dns.DnsProviderCatalog;
import com.adbye.filter.dns.DnsServer;
import com.adbye.filter.model.Prefs;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * DNS Protection screen (Layer 2, launched from ProtectionFragment's DNS row).
 *
 * Fase A: shows the currently selected plain-DNS server (matched against
 * assets/dns_providers.json by upstream IP, falling back to "Custom" if no
 * match), tapping it opens DnsServerListActivity (not yet implemented -
 * placeholder Log.d for now). Below that, a "DNS Filters" section lists
 * DnsFilterEntry rows (not yet implemented - placeholder empty list for now).
 *
 * This is a skeleton commit: DnsServerListActivity and the DNS filter
 * loading are follow-up steps.
 */
public class DnsProtectionActivity extends BaseActivity {
    private static final String TAG = "DnsProtectionActivity";

    private List<DnsProvider> mProviders;
    private RecyclerView mFilterList;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns_protection);
        setTitle(R.string.adbye_protect_dns_title);
        displayBackAction();

        mProviders = DnsProviderCatalog.load(this);

        bindCurrentServerRow();
        bindFilterList();
    }

    private void bindCurrentServerRow() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String currentV4 = Prefs.getDnsServerV4(prefs);

        DnsProvider matched = null;
        for (DnsProvider p : mProviders) {
            DnsServer s = p.getSupportedServer();
            if (s != null && s.upstreams.contains(currentV4)) {
                matched = p;
                break;
            }
        }

        TextView subtitle = findViewById(R.id.current_server_subtitle);
        subtitle.setText(matched != null ? matched.name : getString(R.string.adbye_dns_custom_server));

        findViewById(R.id.current_server_row).setOnClickListener(v -> {
            // Placeholder: DnsServerListActivity not yet implemented.
            android.util.Log.d(TAG, "current_server_row tapped - server picker not yet implemented");
        });
    }

    private void bindFilterList() {
        mFilterList = findViewById(R.id.dns_filter_list);
        mFilterList.setLayoutManager(new LinearLayoutManager(this));

        // Placeholder empty adapter - DNS filter loading is a follow-up step.
        mFilterList.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = new View(parent.getContext());
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

            @Override
            public int getItemCount() {
                return 0;
            }
        });
    }
}
