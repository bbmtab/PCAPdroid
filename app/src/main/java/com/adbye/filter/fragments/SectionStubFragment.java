/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Generic placeholder fragment used by SectionsActivity for sections whose real
 * content is not wired yet (Network / Firewall / Filters-UserRules, as of the
 * 2026-08-11 nav-restructure shell commit). Each caller supplies its own message
 * via newInstance(). Real content lands in dedicated follow-up commits -- this
 * fragment is intentionally dumb and has no logic beyond showing the message.
 */
package com.adbye.filter.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.adbye.filter.R;

public class SectionStubFragment extends Fragment {
    private static final String ARG_MESSAGE = "message";

    public static SectionStubFragment newInstance(String message) {
        SectionStubFragment f = new SectionStubFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MESSAGE, message);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.section_stub, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView tv = view.findViewById(R.id.section_stub_message);
        Bundle args = getArguments();
        if (args != null)
            tv.setText(args.getString(ARG_MESSAGE, ""));
    }
}
