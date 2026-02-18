package io.github.asutorufa.tailscaled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.asutorufa.tailscaled.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "START" -> updateUiState(true)
                "STOP" -> updateUiState(false)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardConsole.setOnClickListener { startActivity(Intent(requireContext(), ConsoleActivity::class.java)) }
        binding.cardPeers.setOnClickListener { startActivity(Intent(requireContext(), PeersActivity::class.java)) }
        binding.cardLogs.setOnClickListener { startActivity(Intent(requireContext(), LogsActivity::class.java)) }
        binding.cardSettings.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        binding.btnAbout.setOnClickListener { showAboutDialog() }
        binding.statusCard.setOnClickListener { toggleVpn() }
    }

    private fun toggleVpn() {
        binding.statusCard.isEnabled = false
        binding.statusCard.postDelayed({ binding.statusCard.isEnabled = true }, 1000)
        val isRunning = ProxyState.isActualRunning()
        val intent = Intent(requireContext(), TailscaledService::class.java)
        if (isRunning) {
            intent.action = "STOP_ACTION"
            requireContext().startService(intent)
        } else {
            intent.action = "START_ACTION"
            requireContext().startForegroundService(intent)
        }
    }

    private fun showAboutDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tailscaled for Android")
            .setMessage("Proxy is running via official Tailscale core.\n\nDeveloper: BroPines\n\nLicense: BSD-3-Clause")
            .setPositiveButton("GitHub") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Asutorufa/tailscale")))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply { addAction("START"); addAction("STOP") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else requireContext().registerReceiver(statusReceiver, filter)
        updateUiState(ProxyState.isActualRunning())
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    private fun updateUiState(isRunning: Boolean) {
        if (isRunning) {
            binding.statusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#dcf8c6")))
            binding.statusTitle.text = "Active"
            binding.statusSubtitle.text = "Proxy is running • Tap to stop"
            binding.statusIcon.setColorFilter(Color.parseColor("#205023"))
        } else {
            binding.statusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#F0F0F0")))
            binding.statusTitle.text = "Stopped"
            binding.statusSubtitle.text = "Tap to connect"
            binding.statusIcon.setColorFilter(Color.GRAY)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}