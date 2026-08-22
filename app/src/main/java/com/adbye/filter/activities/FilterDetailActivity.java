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
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.adbye.filter.Log;
import com.adbye.filter.R;
import com.adbye.filter.filterlists.FilterListEntry;
import com.adbye.filter.filterlists.FilterListManager;
import com.adbye.filter.fragments.ProtectionHotReload;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Layer 3: single-filter detail screen launched from FilterGroupActivity.
 *
 * Shows label/description/homepage/source URL/last updated/rule count/tags
 * for one FilterListEntry (looked up via FilterListManager.findByFname), plus
 * an enable/disable switch mirroring the same setEnabled + reloadRulesOnly
 * pattern used in FilterGroupActivity and the prior flat FiltersFragment.
 */
public class FilterDetailActivity extends BaseActivity {
    private static final String TAG = "FilterDetailActivity";
    public static final String EXTRA_FNAME = "extra_fname";

    private FilterListManager mFilterListManager;
    private FilterListEntry mEntry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_detail);

        mFilterListManager = com.adbye.filter.PCAPdroid.getInstance().getFilterListManager();

        Intent intent = getIntent();
        String fname = (intent != null) ? intent.getStringExtra(EXTRA_FNAME) : null;
        if (fname == null) {
            Log.e(TAG, "missing EXTRA_FNAME");
            finish();
            return;
        }

        mEntry = mFilterListManager.findByFname(fname);
        if (mEntry == null) {
            Log.e(TAG, "no FilterListEntry for fname=" + fname);
            finish();
            return;
        }

        setTitle(mEntry.label);
        displayBackAction();

        bindDescriptionAndSwitch();
        bindHomepageRow();
        bindSourceRow();
        bindLastUpdatedRow();
        bindRulesRow();
        bindTags();
    }

    private void bindDescriptionAndSwitch() {
        TextView desc = findViewById(R.id.detail_description);
        desc.setText((mEntry.description != null && !mEntry.description.isEmpty())
                ? mEntry.description : "");

        MaterialSwitch sw = findViewById(R.id.detail_switch);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(mEntry.isEnabled());
        sw.setOnCheckedChangeListener((view, isChecked) -> {
            boolean ok = mFilterListManager.setEnabled(mEntry.fname, isChecked);
            if (!ok) {
                Log.w(TAG, "setEnabled returned false for fname=" + mEntry.fname);
            }
            ProtectionHotReload.reloadRulesOnly(getApplicationContext(), mEntry.fname);
        });
    }

    private void bindHomepageRow() {
        TextView value = findViewById(R.id.detail_homepage_value);
        String url = mEntry.homepage;
        boolean has = (url != null && !url.isEmpty());
        value.setText(has ? url : getString(R.string.adbye_filter_detail_unknown));
        findViewById(R.id.detail_homepage_row).setOnClickListener(v -> {
            if (has) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                com.adbye.filter.Utils.startActivity(this, browserIntent);
            }
        });
    }

    private void bindSourceRow() {
        TextView value = findViewById(R.id.detail_source_value);
        String url = mEntry.subscriptionUrl;
        boolean has = (url != null && !url.isEmpty());
        value.setText(has ? url : getString(R.string.adbye_filter_detail_unknown));
        findViewById(R.id.detail_source_row).setOnClickListener(v -> {
            if (has) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                com.adbye.filter.Utils.startActivity(this, browserIntent);
            }
        });
    }

    private void bindLastUpdatedRow() {
        TextView value = findViewById(R.id.detail_last_updated_value);
        value.setText((mEntry.timeUpdated != null && !mEntry.timeUpdated.isEmpty())
                ? mEntry.timeUpdated : getString(R.string.adbye_filter_detail_unknown));
    }

    private void bindRulesRow() {
        TextView value = findViewById(R.id.detail_rules_value);
        int n = mEntry.getNumRules();
        value.setText(n > 0 ? String.valueOf(n) : getString(R.string.adbye_filter_detail_unknown));
    }

    private void bindTags() {
        ChipGroup group = findViewById(R.id.detail_tags_container);
        TextView header = findViewById(R.id.detail_tags_header);
        if (mEntry.tagIds == null || mEntry.tagIds.isEmpty()) {
            group.setVisibility(android.view.View.GONE);
            header.setVisibility(android.view.View.GONE);
            return;
        }
        header.setVisibility(android.view.View.VISIBLE);
        group.setVisibility(android.view.View.VISIBLE);
        group.removeAllViews();
        for (Integer tagId : mEntry.tagIds) {
            Chip chip = new Chip(this);
            chip.setText("#" + tagId);
            chip.setClickable(false);
            chip.setCheckable(false);
            group.addView(chip);
        }
    }
}
