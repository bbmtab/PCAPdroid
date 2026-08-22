package com.adbye.filter.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.adbye.filter.AppsLoader;
import com.adbye.filter.CaptureService;
import com.adbye.filter.ConnectionsRegister;
import com.pcapdroid.mitm.MitmAPI;
import com.adbye.filter.PCAPdroid;
import com.adbye.filter.R;
import com.adbye.filter.Utils;
import com.adbye.filter.adapters.AppRuleAdapter;
import com.adbye.filter.activities.EditListActivity;
import com.adbye.filter.interfaces.AppsLoadListener;
import com.adbye.filter.model.AppDescriptor;
import com.adbye.filter.model.AppStats;
import com.adbye.filter.model.Blocklist;
import com.adbye.filter.model.ListInfo;
import com.adbye.filter.model.MatchList;
import com.adbye.filter.model.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * App Management fragment (shell-only, Host A: 5th tab in FirewallActivity).
 *
 * <p>Collapsed row: app icon + name + block-state-color + blocked-connections
 * badge + per-app allowlist-count + expander arrow.  Expanded: block/unblock
 * toggle (MaterialSwitch) with explicit-warning-before-destructive-removal on
 * unblock (hard requirement — no silent allowlist wipe), a read-only
 * allowlist mini-list (populated live from {@link Blocklist#findAppAllowlist}),
 * and Launch / App-info / Edit-allowed-hosts actions.
 *
 * <p>Search is hand-rolled via {@link SearchView.OnQueryTextListener}
 * (matches the in-repo {@code AppsListView} idiom — no {@code Filterable}).
 * Sort is name (default, from {@code AppsLoader}'s natural order) or UID.
 * "Changed"-row tint is per-session-touched (a uid the user toggled in
 * this fragment instance gets the row tinted).
 *
 * <p>Backed entirely by existing code — MatchList APP rules,
 * Blocklist#getAppAllowlist/findAppAllowlist, AppsLoader, ConnectionsRegister,
 * AppIconLoader, EditListActivity (allowlist editor), Prefs firewall
 * master-switch.  Zero native / CaptureService restart — hot-reloads flow
 * through {@link Blocklist#saveAndReload} which internally checks
 * {@link CaptureService#isServiceActive}.
 */
public class AppManagementFragment extends Fragment
        implements SearchView.OnQueryTextListener,
                   AppsLoadListener,
                   AppRuleAdapter.OnRowActionListener {

    private static final int SORT_NAME = 0;
    private static final int SORT_UID  = 1;

    private RecyclerView mListView;
    private SearchView mSearchView;
    private ImageButton mSortBtn;
    private CheckBox mShowSystem;
    private TextView mEmptyText;
    private AppRuleAdapter mAdapter;
    private SharedPreferences mPrefs;

    private List<AppDescriptor> mAllApps = new ArrayList<>();
    private int mSortMode = SORT_NAME;
    private final Set<Integer> mDirtyUids = new HashSet<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_app_management, container, false);

        mListView   = v.findViewById(R.id.app_management_list);
        mSearchView = v.findViewById(R.id.app_mgmt_search);
        mSortBtn    = v.findViewById(R.id.app_mgmt_sort_btn);
        mShowSystem = v.findViewById(R.id.show_system_apps);
        mEmptyText  = v.findViewById(R.id.app_mgmt_empty);

        mListView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new AppRuleAdapter(requireContext());
        mAdapter.setListener(this);
        mListView.setAdapter(mAdapter);

        mSearchView.setOnQueryTextListener(this);
        mSortBtn.setOnClickListener(sortClick -> showSortMenu());
        mShowSystem.setOnCheckedChangeListener((cb, checked) -> rebuildList());

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadApps();
    }

    private void loadApps() {
        (new AppsLoader((AppCompatActivity) requireActivity()))
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    // ---------- AppsLoadListener ----------
    @Override
    public void onAppsInfoLoaded(List<AppDescriptor> apps) {
        mAllApps = apps;
        rebuildList();
    }

    /** Filter → sort → derive Row models → push to adapter. */
    private void rebuildList() {
        if (mAllApps.isEmpty()) return;

        String query = mSearchView.getQuery().toString().trim().toLowerCase();
        boolean showSys = mShowSystem.isChecked();
        boolean tlsDone = Prefs.isTLSDecryptionSetupDone(mPrefs);

        List<AppDescriptor> filtered = new ArrayList<>();
        for (AppDescriptor a : mAllApps) {
            if (!query.isEmpty() && !a.matches(query, false))
                continue;
            if (!showSys && a.isBackgroundSystemApp())
                continue;
            if (tlsDone && MitmAPI.PACKAGE_NAME.equals(a.getPackageName()))
                continue;
            filtered.add(a);
        }

        if (mSortMode == SORT_UID)
            Collections.sort(filtered, Comparator.comparingInt(AppDescriptor::getUid));
        // else: loader already name-sorted

        mAdapter.setItems(deriveRows(filtered));
        mEmptyText.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Compute dynamic Row state from live blocklist + conns register + dirty set. */
    private List<AppRuleAdapter.Row> deriveRows(List<AppDescriptor> apps) {
        Blocklist bl = PCAPdroid.getInstance().getBlocklist();
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        List<AppRuleAdapter.Row> rows = new ArrayList<>(apps.size());

        for (AppDescriptor a : apps) {
            AppRuleAdapter.Row r = new AppRuleAdapter.Row(a);
            int uid = a.getUid();
            String pkg = a.getPackageName();

            r.isBlocked = bl.matchesApp(uid);
            r.dirty = mDirtyUids.contains(uid);

            MatchList al = bl.findAppAllowlist(pkg);
            if (al != null) r.allowlistCount = countIterable(al.iterRules());

            if (reg != null) {
                AppStats st = reg.getAppStats(uid);
                if (st != null) r.blockedConns = st.numBlockedConnections;
            }

            rows.add(r);
        }
        return rows;
    }

    private static int countIterable(Iterator<?> it) {
        int n = 0;
        while (it.hasNext()) { it.next(); n++; }
        return n;
    }

    private void showSortMenu() {
        PopupMenu menu = new PopupMenu(requireContext(), mSortBtn);
        menu.getMenu().add(0, SORT_NAME, 0, R.string.app_mgmt_sort_by_name);
        menu.getMenu().add(0, SORT_UID,  0, R.string.app_mgmt_sort_by_uid);
        menu.setOnMenuItemClickListener(item -> {
            int mode = item.getItemId();
            if (mode != mSortMode) {
                mSortMode = mode;
                rebuildList();
            }
            return true;
        });
        menu.show();
    }

    // ---------- OnRowActionListener ----------

    @Override
    public void onExpandToggle(int position) {
        List<AppRuleAdapter.Row> snap = mAdapter.getSnapshot();
        if (position < 0 || position >= snap.size()) return;
        mAdapter.toggleExpand(snap.get(position).app.getUid());
    }

    @Override
    public void onToggleBlock(AppRuleAdapter.Row row, boolean newBlockedState) {
        Blocklist bl = PCAPdroid.getInstance().getBlocklist();
        String pkg = row.app.getPackageName();
        int uid = row.app.getUid();
        String name = row.app.getName();

        if (newBlockedState) {
            // Block the app (add APP rule)
            if (!bl.addApp(pkg)) {
                Utils.showToastLong(requireContext(), R.string.app_mgmt_block_failed);
                rebuildList();
                return;
            }
        } else {
            // Unblock: HARD-REQ — explicit warning when allowlist data would be lost.
            // Blocklist#removeRule drops the per-app allowlist because "an allowlist
            // only makes sense while its app is blocked."  We warn first.
            MatchList allowlist = bl.findAppAllowlist(pkg);
            int alSize = (allowlist != null) ? countIterable(allowlist.iterRules()) : 0;

            if (alSize > 0) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.warning)
                        .setMessage(getString(R.string.app_mgmt_unblock_warning,
                                name, alSize))
                        .setPositiveButton(R.string.app_mgmt_unblock_action,
                                (d, w) -> {
                                    bl.removeApp(pkg);
                                    bl.saveAndReload();
                                    mDirtyUids.add(uid);
                                    rebuildList();
                                })
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> rebuildList())
                        .show();
                return; // wait for user decision
            }
            // No allowlist to lose — direct unblock
            bl.removeApp(pkg);
        }

        bl.saveAndReload();
        mDirtyUids.add(uid);
        rebuildList();
    }

    @Override
    public void onEditAllowlist(AppRuleAdapter.Row row) {
        Intent intent = new Intent(requireContext(), EditListActivity.class);
        intent.putExtra(EditListActivity.LIST_TYPE_EXTRA, ListInfo.Type.APP_ALLOWLIST);
        intent.putExtra(EditListActivity.APP_PACKAGE_EXTRA, row.app.getPackageName());
        startActivity(intent);
    }

    @Override
    public void onLaunch(AppRuleAdapter.Row row) {
        Intent i = requireContext().getPackageManager()
                .getLaunchIntentForPackage(row.app.getPackageName());
        if (i != null) startActivity(i);
    }

    @Override
    public void onAppInfo(AppRuleAdapter.Row row) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", row.app.getPackageName(), null));
        startActivity(i);
    }

    // ---------- SearchView.OnQueryTextListener (hand-rolled, AppsListView idiom) ----------

    @Override
    public boolean onQueryTextSubmit(String query) {
        rebuildList();
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        rebuildList();
        return true;
    }
}