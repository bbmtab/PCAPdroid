package com.adbye.filter.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.AppIconLoader;
import com.adbye.filter.CaptureService;
import com.adbye.filter.PCAPdroid;
import com.adbye.filter.R;
import com.adbye.filter.filterlists.BypassManager;
import com.adbye.filter.model.AppDescriptor;
import com.adbye.filter.model.Blocklist;
import com.adbye.filter.model.MatchList;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * RecyclerView adapter for the App Management fragment (shell-only).
 * A thin binder over pre-built Row models — the fragment owns dynamic
 * state; this adapter binds it to the views and forwards actions.
 *
 * <p>Single-expansion accordion: only one row is expanded at a time.
 * Expansion state resets on list-replace (preserved by uid-rescan).
 */
public class AppRuleAdapter extends RecyclerView.Adapter<AppRuleAdapter.RowHolder> {

    public static class Row {
        public final AppDescriptor app;
        public boolean isBlocked;
        public int blockedConns;
        public int allowlistCount;
        public boolean dirty;

        public Row(AppDescriptor app) {
            this.app = app;
        }
    }

    public interface OnRowActionListener {
        void onExpandToggle(int position);
        void onToggleBlock(Row row, boolean newBlockedState);
        void onEditAllowlist(Row row);
        void onLaunch(Row row);
        void onAppInfo(Row row);
    }

    private final Context mContext;
    private final List<Row> mItems = new ArrayList<>();
    private int mExpandedUid = -1;
    private OnRowActionListener mListener;

    public AppRuleAdapter(Context ctx) {
        mContext = ctx;
    }

    public void setListener(OnRowActionListener listener) {
        mListener = listener;
    }

    public void setItems(List<Row> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    /** Snapshot of current items — call from the fragment to resolve
     *  uid→position after a toggle-event. */
    public List<Row> getSnapshot() {
        return new ArrayList<>(mItems);
    }

    public int indexOfUid(int uid) {
        for (int i = 0; i < mItems.size(); i++)
            if (mItems.get(i).app.getUid() == uid) return i;
        return -1;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(mContext).inflate(R.layout.app_rule_row, parent, false);
        return new RowHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder h, int position) {
        final Row row = mItems.get(position);
        final AppDescriptor app = row.app;
        final String pkg = app.getPackageName();
        final int uid = app.getUid();

        AppIconLoader.setIcon(h.icon, app, null);
        h.name.setText(app.getName());
        h.packageTv.setText(pkg);

        int blockedColor = ContextCompat.getColor(mContext, R.color.statusError);

        if (row.isBlocked) {
            h.state.setText(R.string.app_mgmt_blocked_state);
            h.state.setTextColor(blockedColor);
            h.name.setTextColor(blockedColor);
        } else {
            h.state.setText(R.string.app_mgmt_allowed_state);
            h.state.setTextColor(resolveTxPrimary());
            h.name.setTextColor(resolveTxPrimary());
        }

        if (row.blockedConns > 0) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(mContext.getString(R.string.app_mgmt_blocked_conns_badge, row.blockedConns));
        } else {
            h.badge.setVisibility(View.GONE);
        }

        if (row.allowlistCount > 0) {
            h.allowlistCount.setVisibility(View.VISIBLE);
            h.allowlistCount.setText(mContext.getString(R.string.app_mgmt_allowlist_count, row.allowlistCount));
        } else {
            h.allowlistCount.setVisibility(View.GONE);
        }

        boolean expanded = (mExpandedUid == uid);
        h.expandedSection.setVisibility(expanded ? View.VISIBLE : View.GONE);
        h.expandArrow.setRotation(expanded ? 180f : 0f);

        // dirty-row tint (preserves selectableItemBackground via overlay tint)
        if (row.dirty) {
            int base = ContextCompat.getColor(mContext, R.color.statusError);
            int tinted = (base & 0x00FFFFFF) | 0x33000000; // ~20 % alpha
            ViewCompat.setBackgroundTintList(h.itemView, ColorStateList.valueOf(tinted));
        } else {
            ViewCompat.setBackgroundTintList(h.itemView, null);
        }

        h.header.setOnClickListener(v -> {
            if (mListener != null) mListener.onExpandToggle(position);
        });

        if (expanded) {
            bindExpanded(h, row, position);
        }
    }

    private void bindExpanded(RowHolder h, Row row, int position) {
        h.blockSwitch.setOnCheckedChangeListener(null);
        h.blockSwitch.setChecked(row.isBlocked);
        h.blockLabel.setText(row.isBlocked
                ? R.string.app_mgmt_unblock_action : R.string.app_mgmt_block_action);
        h.blockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != row.isBlocked && mListener != null) {
                mListener.onToggleBlock(row, isChecked);
            }
        });

        // ---- Filter HTTPS toggle ----
        final String pkg = row.app.getPackageName();
        final int uid = row.app.getUid();
        final boolean isHttpsExempt = BypassManager.get(mContext).isFilterHttpsExempt(pkg);
        h.filterHttpsSwitch.setOnCheckedChangeListener(null);
        h.filterHttpsSwitch.setChecked(!isHttpsExempt);   // checked = filter HTTPS (not exempt)
        h.filterHttpsLabel.setText(R.string.app_mgmt_filter_https_label);
        h.filterHttpsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Switch ON = filter HTTPS active = app NOT exempt.
                BypassManager.get(mContext).removeFilterHttpsExempt(pkg);
                CaptureService.setFilterHttpsExempt(uid, false);
            } else {
                // Switch OFF = exempt from HTTPS filtering.
                BypassManager.get(mContext).addFilterHttpsExempt(pkg);
                CaptureService.setFilterHttpsExempt(uid, true);
            }
        });

        if (row.allowlistCount == 0) {
            h.allowlistCountSubhead.setVisibility(View.GONE);
            h.allowlistEmpty.setVisibility(View.VISIBLE);
            h.allowlistContainer.removeAllViews();
        } else {
            h.allowlistCountSubhead.setVisibility(View.VISIBLE);
            h.allowlistCountSubhead.setText(mContext.getString(
                    R.string.app_mgmt_allowlist_count, row.allowlistCount));
            h.allowlistEmpty.setVisibility(View.GONE);
            populateAllowlistContainer(h.allowlistContainer, row.app.getPackageName());
        }

        h.editAllowlist.setOnClickListener(v -> {
            if (mListener != null) mListener.onEditAllowlist(row);
        });
        h.launchBtn.setOnClickListener(v -> {
            if (mListener != null) mListener.onLaunch(row);
        });
        h.appInfoBtn.setOnClickListener(v -> {
            if (mListener != null) mListener.onAppInfo(row);
        });
    }

    /** Populates the allowlist mini-list live from the Blocklist (reads
     *  at bind-time so it stays fresh — the owning sub-system is the
     *  same Blocklist the toggle writes to via saveAndReload). */
    private void populateAllowlistContainer(LinearLayout container, String pkg) {
        container.removeAllViews();
        Blocklist bl = PCAPdroid.getInstance().getBlocklist();
        MatchList allowlist = bl.findAppAllowlist(pkg);
        if (allowlist == null) return;

        Iterator<MatchList.Rule> iter = allowlist.iterRules();
        int sec = resolveTxSecondary();
        float density = mContext.getResources().getDisplayMetrics().density;
        int padTop = (int) (4f * density);
        int shown = 0;
        int cap = 64; // prevent a runaway allowlist from exploding a row

        while (iter.hasNext() && (shown < cap)) {
            MatchList.Rule rule = iter.next();
            TextView tv = new TextView(mContext);
            tv.setText(rule.getValue().toString());
            tv.setTextSize(12f);
            tv.setTextColor(sec);
            tv.setPadding(0, padTop, 0, 0);
            container.addView(tv);
            shown++;
        }
    }

    /** Toggle single-expansion accordion.  Collapse old, expand new
     *  (or collapse if tapping the already-expanded row). */
    public void toggleExpand(int uid) {
        int prev = mExpandedUid;
        if (prev == uid) {
            mExpandedUid = -1;
            int pos = indexOfUid(prev);
            if (pos >= 0) notifyItemChanged(pos);
        } else {
            mExpandedUid = uid;
            if (prev != -1) {
                int ppos = indexOfUid(prev);
                if (ppos >= 0) notifyItemChanged(ppos);
            }
            int pos = indexOfUid(uid);
            if (pos >= 0) notifyItemChanged(pos);
        }
    }

    public int getExpandedUid() {
        return mExpandedUid;
    }

    private int resolveTxPrimary() {
        TypedValue tv = new TypedValue();
        if (mContext.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)
                && tv.resourceId != 0) {
            return ContextCompat.getColor(mContext, tv.resourceId);
        }
        return 0xFF212121;
    }

    private int resolveTxSecondary() {
        TypedValue tv = new TypedValue();
        if (mContext.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true)
                && tv.resourceId != 0) {
            return ContextCompat.getColor(mContext, tv.resourceId);
        }
        return 0xFF757575;
    }

    static class RowHolder extends RecyclerView.ViewHolder {
        final LinearLayout header;
        final View expandedSection;
        final ImageView icon;
        final TextView name;
        final TextView packageTv;
        final TextView state;
        final TextView badge;
        final TextView allowlistCount;
        final ImageButton expandArrow;
        final MaterialSwitch blockSwitch;
        final TextView blockLabel;
        final MaterialSwitch filterHttpsSwitch;
        final TextView filterHttpsLabel;
        final TextView allowlistCountSubhead;
        final LinearLayout allowlistContainer;
        final TextView allowlistEmpty;
        final Button editAllowlist;
        final Button launchBtn;
        final Button appInfoBtn;

        RowHolder(View v) {
            super(v);
            header      = v.findViewById(R.id.app_rule_header);
            expandedSection = v.findViewById(R.id.app_rule_expanded);
            icon        = v.findViewById(R.id.app_icon);
            name        = v.findViewById(R.id.app_name);
            packageTv   = v.findViewById(R.id.app_package);
            state       = v.findViewById(R.id.app_state);
            badge       = v.findViewById(R.id.app_badge);
            allowlistCount = v.findViewById(R.id.app_allowlist_count);
            expandArrow = v.findViewById(R.id.app_expand);
            blockSwitch = v.findViewById(R.id.app_block_switch);
            blockLabel  = v.findViewById(R.id.app_block_label);
            filterHttpsSwitch = v.findViewById(R.id.app_filter_https_switch);
            filterHttpsLabel  = v.findViewById(R.id.app_filter_https_label);
            allowlistCountSubhead = v.findViewById(R.id.app_allowlist_count_subhead);
            allowlistContainer = v.findViewById(R.id.app_allowlist_container);
            allowlistEmpty = v.findViewById(R.id.app_allowlist_empty);
            editAllowlist = v.findViewById(R.id.app_edit_allowlist);
            launchBtn   = v.findViewById(R.id.app_launch_btn);
            appInfoBtn  = v.findViewById(R.id.app_appinfo_btn);
        }
    }
}