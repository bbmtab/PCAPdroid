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
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.Log;
import com.adbye.filter.R;
import com.adbye.filter.filterlists.FilterListEntry;
import com.adbye.filter.filterlists.FilterListManager;
import com.adbye.filter.filterlists.FilterListManager.Category;
import com.adbye.filter.fragments.FiltersFragment;
import com.adbye.filter.fragments.ProtectionHotReload;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2: per-category filter list detail launched from FiltersFragment.
 *
 * Shows one row per FilterListEntry in the requested category with a
 * MaterialSwitch bound to {@link FilterListManager#setEnabled(String, boolean)}.
 * Toggling a switch persists the choice and triggers
 * {@link ProtectionHotReload#reloadRulesOnly(Context, String)} without a
 * service restart. Back-arrow / up navigation returns to FiltersFragment.
 */
public class FilterGroupActivity extends BaseActivity {
    private static final String TAG = "FilterGroupActivity";
    public static final String EXTRA_CATEGORY = "extra_category";

    private FilterListManager mFilterListManager;
    private Category mCategory;
    private final List<Row> mRows = new ArrayList<>();
    private RecyclerView mRecycler;

    public static class Row {
        final String fname;
        final String label;
        final String description;
        boolean enabled;

        Row(String fname, String label, String description, boolean enabled) {
            this.fname = fname;
            this.label = label;
            this.description = description;
            this.enabled = enabled;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_group);

        mFilterListManager = com.adbye.filter.PCAPdroid.getInstance().getFilterListManager();

        Intent intent = getIntent();
        if (intent == null || !intent.hasExtra(EXTRA_CATEGORY)) {
            Log.e(TAG, "missing EXTRA_CATEGORY");
            finish();
            return;
        }

        String catName = intent.getStringExtra(EXTRA_CATEGORY);
        mCategory = Category.valueOf(catName);

        setTitle(FiltersFragment.categoryDisplayLabel(mCategory));
        displayBackAction();

        loadRows();
        bindList();
    }

    private void loadRows() {
        mRows.clear();
        List<FilterListEntry> entries = mFilterListManager.snapshot();
        for (FilterListEntry e : entries) {
            if (e.category != mCategory) continue;
            mRows.add(new Row(e.fname, e.label, e.description, e.isEnabled()));
        }
        // Stable order by label, case-insensitive.
        mRows.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private void bindList() {
        mRecycler = findViewById(R.id.filter_group_list);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(new Adapter());
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.filter_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = mRows.get(position);
            h.title.setText(r.label);
            h.subtitle.setText((r.description != null && !r.description.isEmpty())
                    ? r.description : "");
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(r.enabled);
            h.sw.setOnCheckedChangeListener((view, isChecked) -> {
                r.enabled = isChecked;
                boolean ok = mFilterListManager.setEnabled(r.fname, isChecked);
                if (!ok) {
                    Log.w(TAG, "setEnabled returned false for fname=" + r.fname);
                }
                ProtectionHotReload.reloadRulesOnly(
                        getApplicationContext(), r.fname);
            });
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(FilterGroupActivity.this, FilterDetailActivity.class);
                intent.putExtra(FilterDetailActivity.EXTRA_FNAME, r.fname);
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return mRows.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;
            final MaterialSwitch sw;
            final View itemView;
            VH(@NonNull View v) {
                super(v);
                itemView = v;
                title    = v.findViewById(R.id.row_title);
                subtitle = v.findViewById(R.id.row_subtitle);
                sw       = v.findViewById(R.id.row_switch);
            }
        }
    }
}