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
import androidx.appcompat.app.AlertDialog
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
        
        // Кнопка About
        binding.btnAbout.setOnClickListener { showAboutDialog() }

        // Главная кнопка-карточка
        binding.statusCard.setOnClickListener {
            toggleVpn()
        }
    }

    private fun toggleVpn() {
        // Блокируем кратковременно, чтобы избежать двойных нажатий
        binding.statusCard.isEnabled = false
        binding.statusCard.postDelayed({ binding.statusCard.isEnabled = true }, 1000)

        val isRunning = ProxyState.isActualRunning()
        val intent = Intent(requireContext(), TailscaledService::class.java)
        
        if (isRunning) {
            intent.action = "STOP_ACTION"
            requireContext().startService(intent)
            // Оптимистичное обновление UI
            updateUiState(false)
        } else {
            intent.action = "START_ACTION"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            updateUiState(true)
        }
    }

    private fun showAboutDialog() {
        val message = """
            Tailscaled for Android
            v${BuildConfig.VERSION_NAME}
            
            Developer: BroPines
            
            Native wrapper for Tailscale node agent.
            
            Open Source Licenses:
            • Tailscale (BSD-3-Clause)
            • Material Components (Apache 2.0)
            • Gson (Apache 2.0)
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("About")
            .setMessage(message)
            .setPositiveButton("GitHub Repo") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Asutorufa/tailscale")))
            }
            .setNeutralButton("Licenses") { _, _ ->
                 // Можно добавить переход на отдельную активность с лицензиями, если нужно
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
            // Мягкий зеленый (Material 3 Style)
            val activeColor = Color.parseColor("#dcf8c6") // Или любой другой приятный оттенок
            val activeText = Color.parseColor("#205023")
            
            binding.statusCard.setCardBackgroundColor(ColorStateList.valueOf(activeColor))
            binding.statusTitle.text = "Active"
            binding.statusTitle.setTextColor(activeText)
            binding.statusSubtitle.text = "Proxy is running • Tap to stop"
            binding.statusSubtitle.setTextColor(activeText)
            binding.statusIcon.setColorFilter(activeText)
            
        } else {
            // Серый (Inactive)
            // Используем стандартные цвета темы через ContextCompat или просто серый
            val inactiveColor = ContextCompat.getColor(requireContext(), com.google.android.material.R.color.material_dynamic_neutral90)
            val inactiveText = Color.BLACK 

            binding.statusCard.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#F0F0F0"))) // Светло-серый
            binding.statusTitle.text = "Stopped"
            binding.statusTitle.setTextColor(Color.BLACK)
            binding.statusSubtitle.text = "Tap to connect"
            binding.statusSubtitle.setTextColor(Color.GRAY)
            binding.statusIcon.setColorFilter(Color.GRAY)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}