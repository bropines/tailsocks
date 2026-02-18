package io.github.asutorufa.tailscaled

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import io.github.asutorufa.tailscaled.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    
    // Основные настройки приложения
    private val prefs by lazy { getSharedPreferences("appctr", Context.MODE_PRIVATE) }
    // Отдельное хранилище для списка ключей
    private val keyPrefs by lazy { getSharedPreferences("auth_keys_store", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Настройка тулбара (кнопка назад)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 1. Инициализация всех компонентов
        loadSettings()          // Загружаем сохраненные данные в поля
        setupListeners()        // Вешаем слушатели изменений
        setupMultiKeyLogic()    // Логика выбора ключей
        setupExpandableSections() // Логика сворачивания/разворачивания категорий
        setupFabRestart()       // Кнопка перезапуска сервиса
    }

    // --- ЛОГИКА РАСКРЫВАЮЩИХСЯ КАТЕГОРИЙ ---
    private fun setupExpandableSections() {
        // Карта: Заголовок -> Контейнер с настройками
        val sections = mapOf(
            binding.headerGeneral to binding.containerGeneral,
            binding.headerNetwork to binding.containerNetwork,
            binding.headerExitNode to binding.containerExitNode,
            binding.headerAdvanced to binding.containerAdvanced
        )

        sections.forEach { (header, container) ->
            // При старте проверяем: если в контейнере что-то включено, можно бы его открыть,
            // но пока оставим закрытым для чистоты, или откроем General по умолчанию.
            
            header.setOnClickListener {
                toggleSection(header, container)
            }
        }
        
        // По умолчанию откроем General
        if (binding.containerGeneral.visibility == View.GONE) {
            toggleSection(binding.headerGeneral, binding.containerGeneral)
        }
    }

    private fun toggleSection(header: TextView, container: ViewGroup) {
        // Добавляем простую анимацию
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup, AutoTransition())

        if (container.visibility == View.VISIBLE) {
            container.visibility = View.GONE
            // Стрелка вниз
            header.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
        } else {
            container.visibility = View.VISIBLE
            // Стрелка вверх
            header.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_up_float, 0)
        }
    }

    // --- ЛОГИКА МУЛЬТИ-КЛЮЧЕЙ ---
    private fun setupMultiKeyLogic() {
        // Клик по иконке списка в поле AuthKey
        binding.authKeyLayout.setEndIconOnClickListener {
            showKeysDialog()
        }
        
        // Устанавливаем иконку и описание
        binding.authKeyLayout.setEndIconDrawable(android.R.drawable.ic_menu_sort_by_size)
        binding.authKeyLayout.setEndIconContentDescription("Select Saved Key")
    }

    private fun showKeysDialog() {
        val keysSet = keyPrefs.getStringSet("keys_list", emptySet()) ?: emptySet()
        val keysList = keysSet.toList().sorted()
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, keysList)

        AlertDialog.Builder(this)
            .setTitle("Manage Auth Keys")
            .setAdapter(adapter) { _, which ->
                val selectedKey = keysList[which]
                binding.authKey.setText(selectedKey)
                saveStr("authkey", selectedKey)
            }
            .setPositiveButton("Add New") { _, _ ->
                showAddKeyDialog()
            }
            .setNeutralButton("Clear All") { _, _ ->
                keyPrefs.edit().clear().apply()
                Toast.makeText(this, "All saved keys cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAddKeyDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = "tskey-auth-..."
        
        AlertDialog.Builder(this)
            .setTitle("Add Auth Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    val current = keyPrefs.getStringSet("keys_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    current.add(newKey)
                    keyPrefs.edit().putStringSet("keys_list", current).apply()
                    
                    // Сразу подставляем и сохраняем
                    binding.authKey.setText(newKey)
                    saveStr("authkey", newKey)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- ЗАГРУЗКА И СОХРАНЕНИЕ ---
    private fun loadSettings() {
        // General
        binding.authKey.setText(prefs.getString("authkey", ""))
        binding.hostname.setText(prefs.getString("hostname", ""))
        binding.loginServer.setText(prefs.getString("login_server", ""))
        binding.switchForceBg.isChecked = prefs.getBoolean("force_bg", false)
        
        // Network
        binding.socks5Addr.setText(prefs.getString("socks5", "127.0.0.1:1055"))
        binding.swAcceptRoutes.isChecked = prefs.getBoolean("accept_routes", false)
        binding.swAcceptDns.isChecked = prefs.getBoolean("accept_dns", true)
        
        // Exit Node
        binding.swAdvertiseExitNode.isChecked = prefs.getBoolean("advertise_exit_node", false)
        binding.exitNodeIp.setText(prefs.getString("exit_node_ip", ""))
        binding.swAllowLan.isChecked = prefs.getBoolean("exit_node_allow_lan", false)
        
        // Advanced
        binding.sshAddr.setText(prefs.getString("sshserver", "127.0.0.1:1056"))
        binding.extraArgs.setText(prefs.getString("extra_args_raw", ""))
    }

    private fun setupListeners() {
        // Text Fields (сохраняем сразу при изменении)
        binding.authKey.doAfterTextChanged { saveStr("authkey", it.toString()) }
        binding.hostname.doAfterTextChanged { saveStr("hostname", it.toString()) }
        binding.loginServer.doAfterTextChanged { saveStr("login_server", it.toString()) }
        binding.socks5Addr.doAfterTextChanged { saveStr("socks5", it.toString()) }
        binding.exitNodeIp.doAfterTextChanged { saveStr("exit_node_ip", it.toString()) }
        
        binding.sshAddr.doAfterTextChanged { saveStr("sshserver", it.toString()) }
        binding.extraArgs.doAfterTextChanged { saveStr("extra_args_raw", it.toString()) }

        // Switches
        binding.switchForceBg.setOnCheckedChangeListener { _, v -> saveBool("force_bg", v) }
        
        binding.swAcceptRoutes.setOnCheckedChangeListener { _, v -> saveBool("accept_routes", v) }
        binding.swAcceptDns.setOnCheckedChangeListener { _, v -> saveBool("accept_dns", v) }
        
        binding.swAdvertiseExitNode.setOnCheckedChangeListener { _, v -> saveBool("advertise_exit_node", v) }
        binding.swAllowLan.setOnCheckedChangeListener { _, v -> saveBool("exit_node_allow_lan", v) }
    }

    // --- КНОПКА РЕСТАРТА ---
    private fun setupFabRestart() {
        binding.fabRestart.setOnClickListener {
            // Визуальный отклик - отключаем кнопку ненадолго
            it.isEnabled = false
            
            // 1. Отправляем сигнал остановки
            val stopIntent = Intent(this, TailscaledService::class.java).apply {
                action = "STOP_ACTION"
            }
            startService(stopIntent)

            // 2. Через полсекунды запускаем снова
            Handler(Looper.getMainLooper()).postDelayed({
                val startIntent = Intent(this, TailscaledService::class.java).apply {
                    action = "START_ACTION"
                }
                ContextCompat.startForegroundService(this, startIntent)
                
                Toast.makeText(this, "Tailscaled Restarting...", Toast.LENGTH_SHORT).show()
                it.isEnabled = true
            }, 800)
        }
    }

    // Утилиты сохранения
    private fun saveStr(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun saveBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}