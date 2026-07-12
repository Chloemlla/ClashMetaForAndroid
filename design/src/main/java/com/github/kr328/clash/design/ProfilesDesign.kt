package com.github.kr328.clash.design

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.core.view.ViewCompat
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignProfilesBinding
import com.github.kr328.clash.design.databinding.DialogProfilesMenuBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.Profile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfilesDesign(context: Context) : Design<ProfilesDesign.Request>(context) {
    sealed class Request {
        object UpdateAll : Request()
        object Create : Request()
        data class Active(val profile: Profile) : Request()
        data class Update(val profile: Profile) : Request()
        data class Edit(val profile: Profile) : Request()
        data class Duplicate(val profile: Profile) : Request()
        data class Delete(val profile: Profile) : Request()
    }

    private val binding = DesignProfilesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ProfileAdapter(context, this::requestActive, this::showMenu)

    private val allUpdating: Boolean
        get() = adapter.states.allUpdating
    private val rotateAnimation : Animation = AnimationUtils.loadAnimation(context, R.anim.rotate_infinite)

    override val root: View
        get() = binding.root

    suspend fun patchProfiles(profiles: List<Profile>) {
        adapter.apply {
            patchDataSet(this::profiles, profiles, id = { it.uuid })
        }

        val updatable = withContext(Dispatchers.Default) {
            profiles.any { it.imported && it.type != Profile.Type.File }
        }

        withContext(Dispatchers.Main) {
            binding.updateView.visibility = if (updatable) View.VISIBLE else View.GONE
            binding.emptyView.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            binding.mainList.recyclerList.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                requests.trySend(Request.Edit(profile))
            }
        }
    }

    fun updateElapsed() {
        adapter.updateElapsed()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    private fun showMenu(profile: Profile) {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogProfilesMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog
        binding.profile = profile

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun requestUpdateAll() {
        if (!adapter.states.beginUpdateAll()) return

        changeUpdateAllButtonStatus()
        if (requests.trySend(Request.UpdateAll).isFailure) {
            finishUpdateAll()
        }
    }

    fun finishUpdateAll() {
        adapter.states.finishUpdateAll()
        changeUpdateAllButtonStatus()
    }

    fun requestCreate() {
        requests.trySend(Request.Create)
    }

    private fun requestActive(profile: Profile) {
        requests.trySend(Request.Active(profile))
    }

    fun requestUpdate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Update(profile))

        dialog.dismiss()
    }

    fun requestEdit(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Edit(profile))

        dialog.dismiss()
    }

    fun requestDuplicate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Duplicate(profile))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, profile: Profile) {
        dialog.dismiss()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_profile)
            .setMessage(context.getString(R.string.delete_profile_confirmation, profile.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                requests.trySend(Request.Delete(profile))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun changeUpdateAllButtonStatus() {
        binding.updateView.isEnabled = !allUpdating
        binding.updateView.isClickable = !allUpdating
        binding.updateView.alpha = if (allUpdating) 0.6f else 1f
        ViewCompat.setStateDescription(
            binding.updateView,
            if (allUpdating) context.getString(R.string.update_all_in_progress) else null
        )

        if (allUpdating) {
            binding.updateView.startAnimation(rotateAnimation)
        } else {
            binding.updateView.clearAnimation()
        }
    }
}

