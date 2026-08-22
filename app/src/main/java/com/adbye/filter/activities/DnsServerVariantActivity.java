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
import com.adbye.filter.dns.DnsServer;
import com.adbye.filter.model.Prefs;

import java.util.List;

/**
 * DNS server variant picker (Layer 3 of DNS Protection), launched from
 * DnsServerListActivity's provider row.
 *
 * Lists every DnsServer variant (plain/DoH/DoT/DoQ/DNSCrypt) belonging to
 * ONE provider. Only variants where DnsServer#isSupportedByBackend() is true
 * (plain "dns" type, per Fase A's native capability) are selectable; the
 * rest render greyed-out with a "coming soon" subtitle so the UI documents
 * the intended surface for future backend work (DoH/DoT/DoQ resolver, not
 * yet implemented) without pretending to be functional.
 *
 * Selecting a supported variant writes BOTH the provider id and server id
 * (Prefs.PREF_DNS_PROVIDER_ID / PREF_DNS_SERVER_ID) plus the upstream IP
 * (Prefs.PREF_DNS_SERVER_V4) - the id pair disambiguates providers that
 * happen to share an upstream IP (e.g. LibreDNS's 3 variants all resolve
 * to the same IP), which the plain IP-only match used previously conflated.
 */
public class DnsServerVariantActivity extends BaseActivity {
    public static final String EXTRA_PROVIDER_ID = "extra_provider_id";

    private DnsProvider mProvider;
    private int mCurrentProviderId;
    private int mCurrentServerId;
    private RecyclerView mRecycler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns_server_variant);
        displayBackAction();

        int providerId = getIntent().getIntExtra(EXTRA_PROVIDER_ID, -1);
        List<DnsProvider> all = DnsProviderCatalog.load(this);
        for (DnsProvider p : all) {
            if (p.providerId == providerId) {
                mProvider = p;
                break;
            }
        }
        if (mProvider == null) {
            finish();
            return;
        }
        setTitle(mProvider.name);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mCurrentProviderId = Prefs.getDnsProviderId(prefs);
        mCurrentServerId = Prefs.getDnsServerId(prefs);

        mRecycler = findViewById(R.id.variant_list);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(new Adapter());
    }

    private String protocolLabel(String type) {
        switch (type) {
            case "dns": return "Plain DNS";
            case "doh": return "DNS-over-HTTPS";
            case "dot": return "DNS-over-TLS";
            case "doq": return "DNS-over-QUIC";
            case "dnscrypt": return "DNSCrypt";
            default: return type;
        }
    }

    private void selectVariant(DnsServer server) {
        if (!server.isSupportedByBackend() || server.upstreams.isEmpty()) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putInt(Prefs.PREF_DNS_PROVIDER_ID, mProvider.providerId)
                .putInt(Prefs.PREF_DNS_SERVER_ID, server.id)
                .putString(Prefs.PREF_DNS_SERVER_V4, server.upstreams.get(0))
                .apply();

        setResult(RESULT_OK);
        finish();
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.dns_variant_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DnsServer server = mProvider.servers.get(position);
            boolean supported = server.isSupportedByBackend();
            boolean isCurrent = supported
                    && mProvider.providerId == mCurrentProviderId
                    && server.id == mCurrentServerId;

            h.title.setText(protocolLabel(server.type));
            h.subtitle.setText(supported
                    ? getString(R.string.adbye_dns_variant_available)
                    : getString(R.string.adbye_dns_variant_coming_soon));
            h.check.setVisibility(isCurrent ? View.VISIBLE : View.GONE);

            h.itemView.setEnabled(supported);
            h.itemView.setAlpha(supported ? 1.0f : 0.4f);
            h.itemView.setOnClickListener(supported ? (v -> selectVariant(server)) : null);
        }

        @Override public int getItemCount() { return mProvider.servers.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title, subtitle;
            final ImageView check;
            final View itemView;
            VH(@NonNull View v) {
                super(v);
                itemView = v;
                title    = v.findViewById(R.id.variant_title);
                subtitle = v.findViewById(R.id.variant_subtitle);
                check    = v.findViewById(R.id.variant_check);
            }
        }
    }
}