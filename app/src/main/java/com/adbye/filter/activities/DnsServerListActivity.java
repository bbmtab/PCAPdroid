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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.R;
import com.adbye.filter.dns.DnsProvider;
import com.adbye.filter.dns.DnsProviderCatalog;
import com.adbye.filter.model.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * DNS provider picker (Layer 2 of DNS Protection), launched from
 * DnsProtectionActivity's "Current DNS server" row.
 *
 * Shows one row per DnsProvider that has at least one usable server variant
 * (DnsProvider#getSupportedServer != null OR has any servers at all - shown
 * even if none are backend-supported yet, so DoH/DoT/DoQ-only providers are
 * still visible/navigable, just fully greyed-out on the next screen).
 *
 * Tapping a provider opens DnsServerVariantActivity (Layer 3) to pick a
 * specific protocol variant within that provider - NOT an immediate
 * selection, since a provider can have multiple variants sharing the same
 * upstream IP (e.g. LibreDNS's 3 variants), which a direct-select-by-IP
 * flow could not disambiguate.
 */
public class DnsServerListActivity extends BaseActivity {
    private List<DnsProvider> mProviders;
    private int mCurrentProviderId;
    private RecyclerView mRecycler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns_server_list);
        setTitle(R.string.adbye_dns_current_server);
        displayBackAction();

        mProviders = DnsProviderCatalog.load(this);

        mRecycler = findViewById(R.id.server_list);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(new Adapter());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh in case a variant was just selected in DnsServerVariantActivity.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mCurrentProviderId = Prefs.getDnsProviderId(prefs);
        if (mRecycler.getAdapter() != null) mRecycler.getAdapter().notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.dns_provider_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DnsProvider p = mProviders.get(position);
            boolean isCurrent = p.providerId == mCurrentProviderId;

            h.title.setText(p.name);
            h.subtitle.setText(p.description != null ? p.description : "");
            h.check.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(DnsServerListActivity.this,
                        DnsServerVariantActivity.class);
                intent.putExtra(DnsServerVariantActivity.EXTRA_PROVIDER_ID, p.providerId);
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return mProviders.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title, subtitle;
            final ImageView check;
            final View itemView;
            VH(@NonNull View v) {
                super(v);
                itemView = v;
                title    = v.findViewById(R.id.provider_title);
                subtitle = v.findViewById(R.id.provider_subtitle);
                check    = v.findViewById(R.id.provider_check);
            }
        }
    }
}