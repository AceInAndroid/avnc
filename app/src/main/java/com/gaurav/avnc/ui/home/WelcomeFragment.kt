/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.FragmentWelcomeBinding
import com.gaurav.avnc.ui.about.AboutActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Simple fragment to show welcome message after major updates
 */
class WelcomeFragment : BottomSheetDialogFragment() {

    private val GITHUB_RELEASE_URL = "${AboutActivity.GIT_REPO_URL}/releases"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        binding.changelogBtn.setOnClickListener { openChangelog() }
        binding.rateBtn.setOnClickListener { openProject() }
        binding.closeBtn.setOnClickListener { dismiss() }
        with(requireDialog() as BottomSheetDialog) {
            setCanceledOnTouchOutside(false)
            behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isFitToContents = true
            }
        }
        return binding.root
    }

    private fun openChangelog() {
        runCatching { startActivity(Intent.parseUri(GITHUB_RELEASE_URL, 0)) }.onFailure { warnMissingBrowser() }
    }

    private fun openProject() {
        runCatching { startActivity(Intent.parseUri(AboutActivity.GIT_REPO_URL, 0)) }
                .onFailure { warnMissingBrowser() }
    }

    private fun warnMissingBrowser() {
        Toast.makeText(requireContext(), R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
    }
}
