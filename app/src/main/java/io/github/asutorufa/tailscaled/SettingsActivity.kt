package io.github.asutorufa.tailscaled

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import io.github.asutorufa.tailscaled.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("appctr", Context.MODE_PRIVATE) }
    private val keyPrefs by lazy { getSharedPreferences("auth_keys_store", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadSettings()
        setupListeners()
        setupMultiKeyLogic()

        binding.fabRestart.setOnClickListener {
            val stopIntent = Intent(this, TailscaledService::class.java).apply {
                action = "STOP_ACTION"
            }
            startService(stopIntent)

            Handler(Looper.getMainLooper()).postDelayed({
                val startIntent = Intent(this, TailscaledService::class.java).apply {
                    action = "START_ACTION"
                }
                ContextCompat.startForegroundService(this, startIntent)
                Toast.makeText(this, "Service Restarted", Toast.LENGTH_SHORT).show()
            }, 500)
        }
    }

    private fun setupMultiKeyLogic() {
        // Заменяем обычное поведение поля AuthKey, добавляем иконку
        binding.authKey.setEndIconOnClickListener {
            showKeysDialog()
        }
        // Меняем иконку на список
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
                    
                    // Auto set current
                    binding.authKey.setText(newKey)
                    saveStr("authkey", newKey)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSettings() {
        binding.authKey.setText(prefs.getString("authkey", ""))
        binding.hostname.setText(prefs.getString("hostname", ""))
        binding.loginServer.setText(prefs.getString("login_server", ""))
        
        binding.socks5Addr.setText(prefs.getString("socks5", "127.0.0.1:1055"))
        binding.sshAddr.setText(prefs.getString("sshserver", "127.0.0.1:1056"))
        
        binding.swAcceptRoutes.isChecked = prefs.getBoolean("accept_routes", false)
        binding.swAcceptDns.isChecked = prefs.getBoolean("accept_dns", true)
        
        binding.swAdvertiseExitNode.isChecked = prefs.getBoolean("advertise_exit_node", false)
        binding.exitNodeIp.setText(prefs.getString("exit_node_ip", ""))
        binding.swAllowLan.isChecked = prefs.getBoolean("exit_node_allow_lan", false)
        
        binding.extraArgs.setText(prefs.getString("extra_args_raw", ""))
        binding.switchForceBg.isChecked = prefs.getBoolean("force_bg", false)
    }

    private fun setupListeners() {
        // Text Fields
        binding.authKey.doAfterTextChanged { saveStr("authkey", it.toString()) }
        binding.hostname.doAfterTextChanged { saveStr("hostname", it.toString()) }
        binding.loginServer.doAfterTextChanged { saveStr("login_server", it.toString()) }
        binding.socks5Addr.doAfterTextChanged { saveStr("socks5", it.toString()) }
        binding.sshAddr.doAfterTextChanged { saveStr("sshserver", it.toString()) }
        binding.exitNodeIp.doAfterTextChanged { saveStr("exit_node_ip", it.toString()) }
        binding.extraArgs.doAfterTextChanged { saveStr("extra_args_raw", it.toString()) }

        // Switches
        binding.swAcceptRoutes.setOnCheckedChangeListener { _, v -> saveBool("accept_routes", v) }
        binding.swAcceptDns.setOnCheckedChangeListener { _, v -> saveBool("accept_dns", v) }
        binding.swAdvertiseExitNode.setOnCheckedChangeListener { _, v -> saveBool("advertise_exit_node", v) }
        binding.swAllowLan.setOnCheckedChangeListener { _, v -> saveBool("exit_node_allow_lan", v) }
        binding.switchForceBg.setOnCheckedChangeListener { _, v -> saveBool("force_bg", v) }
    }

    private fun saveStr(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun saveBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}