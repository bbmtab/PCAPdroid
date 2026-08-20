package com.adbye.filter.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.R;
import com.adbye.filter.filterlists.FilterListManager;
import com.adbye.filter.filterlists.FilterListEntry;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Filters & Rules tab — Layer 1: one row per FilterListManager.Category,
 * showing "X of Y enabled" and navigating to FilterGroupActivity on tap.
 *
 * Replaces the prior flat per-list layout (see e998ae0d) with a grouped
 * view. Per-list toggling now lives in FilterGroupActivity (Layer 2);
 * this fragment is read-only summary + navigation.
 */
public class FiltersFragment extends Fragment {
    private static final String TAG = "FiltersFragment";

    public static class GroupRow {
        final FilterListManager.Category category;
        final int enabledCount;
        final int totalCount;

        GroupRow(FilterListManager.Category category, int enabledCount, int totalCount) {
            this.category = category;
            this.enabledCount = enabledCount;
            this.totalCount = totalCount;
        }
    }

    private final List<GroupRow> mGroupRows = new ArrayList<>();
    private RecyclerView mRecycler;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGroupRows.clear();

        List<FilterListEntry> entries =
                com.adbye.filter.PCAPdroid.getInstance().getFilterListManager().snapshot();

        Map<FilterListManager.Category, int[]> counts =
                new EnumMap<>(FilterListManager.Category.class);
        for (FilterListManager.Category c : FilterListManager.Category.values()) {
            if (c == FilterListManager.Category.CUSTOM) continue; // custom shown separately, later step
            counts.put(c, new int[]{0, 0}); // [enabled, total]
        }

        for (FilterListEntry e : entries) {
            if (e.category == FilterListManager.Category.CUSTOM) continue;
            int[] c = counts.get(e.category);
            if (c == null) continue; // defensive, shouldn't happen
            c[1]++; // total
            if (e.isEnabled()) c[0]++; // enabled
        }

        // Stable display order matches the enum declaration order.
        for (FilterListManager.Category c : FilterListManager.Category.values()) {
            if (c == FilterListManager.Category.CUSTOM) continue;
            int[] counts_ = counts.get(c);
            if (counts_[1] == 0) continue; // skip empty categories (no entries loaded)
            mGroupRows.add(new GroupRow(c, counts_[0], counts_[1]));
        }
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
        mRecycler = view.findViewById(R.id.filters_list);
        mRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecycler.setAdapter(new Adapter());
    }

    /** Human-readable label for a category (used here and reusable by FilterGroupActivity). */
    public static String categoryDisplayLabel(FilterListManager.Category c) {
        switch (c) {
            case AD_BLOCKING: return "Ad Blocking";
            case PRIVACY: return "Privacy";
            case SOCIAL: return "Social";
            case ANNOYANCE: return "Annoyance";
            case SECURITY: return "Security";
            case LANGUAGE: return "Language";
            case OTHER: return "Other";
            case CUSTOM: return "Custom";
            default: return c.key;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.filter_group_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            GroupRow r = mGroupRows.get(position);
            h.title.setText(categoryDisplayLabel(r.category));
            h.subtitle.setText(r.enabledCount + " of " + r.totalCount + " filters enabled");
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(),
                        com.adbye.filter.activities.FilterGroupActivity.class);
                intent.putExtra(com.adbye.filter.activities.FilterGroupActivity.EXTRA_CATEGORY,
                        r.category.name());
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return mGroupRows.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final android.widget.TextView title, subtitle;
            final View itemView;
            VH(@NonNull View v) {
                super(v);
                itemView = v;
                title    = v.findViewById(R.id.group_title);
                subtitle = v.findViewById(R.id.group_subtitle);
            }
        }
    }

    /** Expose rows for testing. */
    @SuppressWarnings("unused")
    public List<GroupRow> getGroupRows() {
        return mGroupRows;
    }
}