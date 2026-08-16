package com.adbye.filter.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.Log;
import com.adbye.filter.R;
import com.adbye.filter.filterlists.FilterListManager;
import com.adbye.filter.filterlists.FilterListEntry;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filters & User Rules tab — per-filter-list enable/disable toggles.
 *
 * Each row shows a filter list's label + human-readable category name and
 * a MaterialSwitch bound to {@link FilterListManager#getFilterListManager}'s
 * per-entry enabled flag. Toggling a switch calls setEnabled() (which persists
 * the choice to SharedPreferences) and then triggers a hot-re-merge via
 * {@link ProtectionHotReload#reloadRulesOnly(Context, String)} so the
 * native engine picks up the change without a service restart.
 *
 * Custom (user-added) lists created via addCustom() are shown at the bottom
 * of the list with a "(custom)" label; the custom list itself is still
 * in scope for display — only addCustom()'s catalog persistence is out of
 * scope (see FilterListManager commit ae54d7a8).
 */
public class FiltersFragment extends Fragment {
    private static final String TAG = "FiltersFragment";

    public static class Row {
        final String fname;
        final String label;
        final String categoryLabel;
        boolean enabled;

        Row(String fname, String label, String categoryLabel, boolean enabled) {
            this.fname = fname;
            this.label = label;
            this.categoryLabel = categoryLabel;
            this.enabled = enabled;
        }
    }

    private final List<Row> mRows = new ArrayList<>();
    private RecyclerView mRecycler;
    private FilterListManager mFilterListManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRows.clear();

        // Snapshot a consistent view under FilterListManager's synchronized lock.
        List<FilterListEntry> entries =
                com.adbye.filter.PCAPdroid.getInstance().getFilterListManager().snapshot();

        // Predefined lists first (sorted by label), then custom lists at the end.
        List<Row> predefined = new ArrayList<>();
        List<Row> custom   = new ArrayList<>();

        for (FilterListEntry e : entries) {
            String catLabel = e.category.key.replace('_', ' ');
            if (e.isCustom()) {
                custom.add(new Row(e.fname, e.label + " (custom)", catLabel, e.isEnabled()));
            } else {
                predefined.add(new Row(e.fname, e.label, catLabel, e.isEnabled()));
            }
        }

        // Sort predefined by label (case-insensitive) for stable ordering.
        predefined.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        mRows.addAll(predefined);
        mRows.addAll(custom);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_filters, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mFilterListManager = com.adbye.filter.PCAPdroid.getInstance().getFilterListManager();
        mRecycler = view.findViewById(R.id.filters_list);
        mRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecycler.setAdapter(new Adapter());
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.filter_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = mRows.get(position);
            h.title.setText(r.label);
            h.subtitle.setText(r.categoryLabel);
            // Temporarily clear listener so setChecked does not fire a toggle.
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(r.enabled);
            h.sw.setOnCheckedChangeListener((view, isChecked) -> {
                r.enabled = isChecked;
                boolean ok = mFilterListManager.setEnabled(r.fname, isChecked);
                if (!ok) {
                    Log.w(TAG, "setEnabled returned false for fname=" + r.fname);
                }
                // Trigger re-merge + native reload. logContext = fname so logs
                // identify which entry triggered the reload.
                ProtectionHotReload.reloadRulesOnly(
                        requireContext().getApplicationContext(), r.fname);
            });
            // Tap anywhere on the row to toggle the switch.
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
                title    = v.findViewById(R.id.row_title);
                subtitle = v.findViewById(R.id.row_subtitle);
                sw       = v.findViewById(R.id.row_switch);
            }
        }
    }

    /** Expose rows for testing. */
    @SuppressWarnings("unused")
    public List<Row> getRows() {
        return mRows;
    }
}