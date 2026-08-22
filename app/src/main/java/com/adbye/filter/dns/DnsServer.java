package com.adbye.filter.dns;

import java.util.List;

/** One resolver endpoint for a DnsProvider (e.g. plain IP, DoH URL, DoT URL). */
public class DnsServer {
    public final int id;
    public final int providerId;
    public final String name;
    public final String type; // "dns", "dnscrypt", "doh", "dot", "doq"
    public final List<String> upstreams;
    public final List<String> features;

    public DnsServer(int id, int providerId, String name, String type,
                      List<String> upstreams, List<String> features) {
        this.id = id;
        this.providerId = providerId;
        this.name = name;
        this.type = type;
        this.upstreams = upstreams;
        this.features = features;
    }

    /** Fase A only supports plain "dns" type servers (matches current native backend). */
    public boolean isSupportedByBackend() {
        return "dns".equals(type);
    }
}
