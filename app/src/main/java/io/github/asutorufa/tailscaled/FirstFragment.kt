package io.github.asutorufa.tailscaled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    // ВОТ ЗДЕСЬ ВСЕ ОБРАБОТЧИКИ КЛИКОВ
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Кнопка консоли
        binding.cardConsole.setOnClickListener {
            startActivity(Intent(requireContext(), ConsoleActivity::class.java))
        }

        // 2. НОВАЯ Кнопка устройств (Peers) - ВОТ ОНА
        binding.cardPeers.setOnClickListener {
            startActivity(Intent(requireContext(), PeersActivity::class.java))
        }

        // 3. Кнопка логов
        binding.cardLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogsActivity::class.java))
        }
        
        // 4. Кнопка настроек
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        // 5. Главная кнопка старт/стоп
        binding.btnAction.setOnClickListener {
            val isRunning = ProxyState.isActualRunning()
            val intent = Intent(requireContext(), TailscaledService::class.java)
            
            if (isRunning) {
                intent.action = "STOP_ACTION"
                requireContext().startService(intent)
                binding.btnAction.isEnabled = false 
            } else {
                intent.action = "START_ACTION"
                requireContext().startForegroundService(intent)
                binding.btnAction.isEnabled = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("START")
            addAction("STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(statusReceiver, filter)
        }

        updateUiState(ProxyState.isActualRunning())
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun updateUiState(isRunning: Boolean) {
        binding.btnAction.isEnabled = true
        
        if (isRunning) {
            binding.statusText.text = getString(R.string.status_running)
            binding.btnAction.text = getString(R.string.action_stop)
            
            val colorGreen = ContextCompat.getColor(requireContext(), android.R.color.holo_green_light)
            binding.statusCard.setCardBackgroundColor(colorGreen)
            binding.statusIcon.setImageResource(R.drawable.ic_launcher_foreground) 
        } else {
            binding.statusText.text = getString(R.string.status_stopped)
            binding.btnAction.text = getString(R.string.action_start)
            
            val typedValue = android.util.TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
            binding.statusCard.setCardBackgroundColor(typedValue.data)
            binding.statusIcon.setImageResource(R.drawable.ic_launcher_foreground)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}