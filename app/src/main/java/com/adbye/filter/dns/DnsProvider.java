package com.adbye.filter.dns;

import java.util.List;

/** One DNS provider (e.g. "AdGuard DNS", "Cloudflare DNS") with one or more DnsServer variants. */
public class DnsProvider {
    public final int providerId;
    public final String name;
    public final String description;
    public final String homepage;
    public final List<DnsServer> servers;

    public DnsProvider(int providerId, String name, String description,
                        String homepage, List<DnsServer> servers) {
        this.providerId = providerId;
        this.name = name;
        this.description = description;
        this.homepage = homepage;
        this.servers = servers;
    }

    /** Returns the first backend-supported (plain "dns") server, or null if none. */
    public DnsServer getSupportedServer() {
        for (DnsServer s : servers) {
            if (s.isSupportedByBackend()) return s;
        }
        return null;
    }
}
