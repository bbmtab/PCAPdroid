package com.adbye.filter.dns;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads app/src/main/assets/dns_providers.json into a list of DnsProvider.
 * Fase A: only the "dns" (plain) DnsServer variant per provider is actionable
 * (see DnsServer#isSupportedByBackend) - the native capture engine only
 * supports plain-IP upstream DNS today (Prefs.PREF_DNS_SERVER_V4/V6).
 * DoH/DoT/DoQ/DNSCrypt variants are parsed and stored for future backend
 * work but are not selectable in Fase A's UI.
 */
public final class DnsProviderCatalog {
    private DnsProviderCatalog() {}

    public static List<DnsProvider> load(Context ctx) {
        List<DnsProvider> result = new ArrayList<>();
        try {
            InputStream is = ctx.getAssets().open("dns_providers.json");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
            is.close();
            String json = buf.toString("UTF-8");
            org.json.JSONArray root = new org.json.JSONArray(json);

            for (int i = 0; i < root.length(); i++) {
                org.json.JSONObject p = root.getJSONObject(i);
                int providerId = p.getInt("providerId");
                String name = p.optString("name", "");
                String description = p.optString("description", "");
                String homepage = p.optString("homepage", "");

                List<DnsServer> servers = new ArrayList<>();
                org.json.JSONArray serversArr = p.optJSONArray("servers");
                if (serversArr != null) {
                    for (int j = 0; j < serversArr.length(); j++) {
                        org.json.JSONObject s = serversArr.getJSONObject(j);
                        int id = s.getInt("id");
                        int pid = s.getInt("providerId");
                        String sname = s.optString("name", "");
                        String type = s.optString("type", "dns");

                        List<String> upstreams = new ArrayList<>();
                        org.json.JSONArray up = s.optJSONArray("upstreams");
                        if (up != null) {
                            for (int k = 0; k < up.length(); k++) upstreams.add(up.getString(k));
                        }

                        List<String> features = new ArrayList<>();
                        org.json.JSONArray feat = s.optJSONArray("features");
                        if (feat != null) {
                            for (int k = 0; k < feat.length(); k++) features.add(feat.getString(k));
                        }

                        servers.add(new DnsServer(id, pid, sname, type, upstreams, features));
                    }
                }
                result.add(new DnsProvider(providerId, name, description, homepage, servers));
            }
        } catch (Exception e) {
            android.util.Log.e("DnsProviderCatalog", "load failed", e);
        }
        return result;
    }
}
