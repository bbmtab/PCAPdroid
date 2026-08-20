/*
 * This file is part of ADBye (PCAPdroid).
 */
package com.adbye.filter.filterlists;

/**
 * Filter list entry â€” descriptor for one downloadable filter list.
 * Distinct from {@link com.adbye.filter.model.FilterDescriptor}
 * which models the per-app HTTP filter used by Connections;
 * this class covers URL subscriptions (EasyList, EasyPrivacy etc.).
 */
public class FilterListEntry {
    public final String label;
    public final FilterListManager.Category category;
    public final String fname;
    public final String url;
    public final String description;
    public final String homepage;
    public final String subscriptionUrl;
    public final String timeUpdated;
    public final java.util.List<Integer> tagIds;

    private boolean isEnabled = true;
    private boolean isCustom = false;
    private boolean isUpToDate = false;
    private boolean isUpdating = false;
    private long lastUpdate = 0;
    private int numRules = 0;

    public FilterListEntry(String label,
                           FilterListManager.Category category,
                           String fname,
                           String url) {
        this.label = label;
        this.category = category;
        this.fname = fname;
        this.url = url;
        this.description = null;
        this.homepage = null;
        this.subscriptionUrl = null;
        this.timeUpdated = null;
        this.tagIds = java.util.Collections.emptyList();
    }

    public FilterListEntry(String label,
                           FilterListManager.Category category,
                           String fname,
                           String url,
                           String description,
                           String homepage,
                           String subscriptionUrl,
                           String timeUpdated,
                           java.util.List<Integer> tagIds) {
        this.label = label;
        this.category = category;
        this.fname = fname;
        this.url = url;
        this.description = description;
        this.homepage = homepage;
        this.subscriptionUrl = subscriptionUrl;
        this.timeUpdated = timeUpdated;
        this.tagIds = tagIds;
    }

    public boolean isEnabled() { return isEnabled; }
    public void setEnabled(boolean v) { this.isEnabled = v; }

    public boolean isCustom() { return isCustom; }
    public void markCustom() { this.isCustom = true; }

    public void setUpdating() { isUpdating = true; isUpToDate = false; }
    public void setOutdated() { isUpdating = false; isUpToDate = false; }
    public void setUpdated(long now) {
        isUpdating = false;
        lastUpdate = now;
        isUpToDate = (now != 0);
    }
    public boolean isUpToDate() { return isUpToDate; }
    public long getLastUpdate() { return lastUpdate; }
    public int getNumRules() { return numRules; }
    public void setNumRules(int n) { this.numRules = n; }
    public boolean isUpdating() { return isUpdating; }
}


