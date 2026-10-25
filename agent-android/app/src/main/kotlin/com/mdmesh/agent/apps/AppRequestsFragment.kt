package com.mdmesh.agent.apps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.mdmesh.agent.ui.compose.MDMeshTheme
import com.mdmesh.agent.web.AdminAccountActivity
import com.mdmesh.agent.web.NetworkAdminNotificationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Activity owns Android navigation/lifecycle; screen state and networking survive recreation in the ViewModel. */
class AppRequestsFragment : androidx.fragment.app.Fragment() {
    private val model: AppRequestsViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, null) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                AppRequestsViewModel(AndroidAppRequestsRepository(requireContext().applicationContext), handle) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.start(arguments?.getBoolean("adminInbox", false) ?: false, consumeSharedPackage(screenIntent))
        acceptApprovalNotification(screenIntent)
        NetworkAdminNotificationService.start(requireContext())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { model.state.collect { state ->
                    if (state.admin || state.signedIn) requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } }
                launch { model.effects.collect { effect -> when (effect) { is AppRequestEffect.OpenPlay -> openPlay(effect.packageName) } } }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.foreground()
                while (true) { delay(30_000); model.poll() }
            }
        }
    }
    private val screenIntent get()=Intent().apply {arguments?.let {putExtras(it)}}
    override fun onCreateView(inflater:android.view.LayoutInflater,container:android.view.ViewGroup?,savedInstanceState:Bundle?):android.view.View = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MDMeshTheme {
                val state by model.state.collectAsStateWithLifecycle()
                AppRequestsScreen(state, AppRequestsActions(
                    back = { (requireActivity() as com.mdmesh.agent.MainActivity).selectDestination(0) }, account = { startActivity(Intent(requireContext(), AdminAccountActivity::class.java)) },
                    refresh = model::refresh, role = model::role, tab = model::tab, page = model::page,
                    picker = model::picker, dismiss = model::dismiss, query = model::query, search = model::search,
                    choose = model::choose, reason = model::reason, submit = model::submit,
                    details = model::details, note = model::note, decide = model::decide,
                    openPlay = model::openPlay, openApproved = model::openApproved,
                ))
            }
        }
    }
    fun acceptIntent(intent: Intent) {
        acceptApprovalNotification(intent)
        consumeSharedPackage(intent)?.let { model.acceptShare(it); return }
        if (intent.getBooleanExtra("adminInbox", false)) model.role(true)
    }
    private fun acceptApprovalNotification(intent: Intent) {
        val id = intent.getIntExtra("installRequestId", 0)
        val scope = intent.getStringExtra("approvalScope")
        intent.removeExtra("installRequestId"); intent.removeExtra("approvalScope")
        if (id <= 0 || scope == null) return
        lifecycleScope.launch {
            if (AppApprovalNotifications.scope(requireContext().applicationContext) == scope) {
                model.role(false)
                model.openApprovedId(id)
            } else model.message("Thiết bị hoặc máy chủ đã thay đổi. Hãy mở lại yêu cầu hiện tại.")
        }
    }
    private fun consumeSharedPackage(intent: Intent): String? = intent.getStringExtra("sharedPackage")?.also { intent.removeExtra("sharedPackage") }
    private fun openPlay(pkg: String?) {
        if (pkg != null && !PlayAppLink.validPackage(pkg)) return
        val uri = Uri.parse(if (pkg == null) "market://search?q=apps&c=apps" else "market://details?id=${Uri.encode(pkg)}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")) }
        catch (_: Exception) { model.message("Không mở được Google Play. Kiểm tra ứng dụng đã có trên máy và được chính sách thiết bị cho phép.") }
    }
}
