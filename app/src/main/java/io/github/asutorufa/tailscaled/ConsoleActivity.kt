package io.github.asutorufa.tailscaled

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import appctr.Appctr
import io.github.asutorufa.tailscaled.databinding.ActivityConsoleBinding
import java.io.File

class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding
    private val prefs: SharedPreferences by lazy { getSharedPreferences("console_presets", Context.MODE_PRIVATE) }
    private val historyFile by lazy { File(filesDir, "console_history.dat") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Восстановление истории из файла
        if (historyFile.exists()) {
            try {
                binding.outputText.text = historyFile.readText()
                scrollToBottom()
            } catch (e: Exception) {
                binding.outputText.text = "Welcome to Tailscale Sandbox...\n$ "
            }
        }

        // Обработка ввода с клавиатуры (Enter)
        binding.inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand()
                true
            } else {
                false
            }
        }

        binding.btnRun.setOnClickListener { runCommand() }
        
        binding.btnClearConsole.setOnClickListener {
            binding.outputText.text = "$ "
            if (historyFile.exists()) historyFile.delete()
        }
        
        binding.btnAddPreset.setOnClickListener { showAddPresetDialog() }

        loadPresets()
        
        // Проверяем, не пришли ли мы сюда из списка устройств с командой (например, ping)
        handleIntent(intent)
    }

    // Это нужно, если консоль уже открыта, и мы нажали "Ping" в списке устройств еще раз
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("CMD")?.let { cmd ->
            if (cmd.isNotEmpty()) {
                binding.inputCommand.setText(cmd)
                execute(cmd)
                // Удаляем экстра, чтобы команда не выполнялась повторно при смене темы или повороте
                intent.removeExtra("CMD")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем историю при выходе
        try {
            historyFile.writeText(binding.outputText.text.toString())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun loadPresets() {
        val childCount = binding.presetsContainer.childCount
        if (childCount > 1) {
            binding.presetsContainer.removeViews(0, childCount - 1)
        }

        addPresetButton("status")
        addPresetButton("netcheck")
        addPresetButton("ping 8.8.8.8")
        addPresetButton("--help")

        val saved = prefs.getStringSet("commands", emptySet()) ?: emptySet()
        saved.sorted().forEach { cmd ->
            addPresetButton(cmd, isCustom = true)
        }
    }

    private fun addPresetButton(command: String, isCustom: Boolean = false) {
        val btn = Button(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_TonalButton), null, 0).apply {
            text = command
            setOnClickListener { 
                execute(command) 
                binding.inputCommand.requestFocus()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            
            if (isCustom) {
                setOnLongClickListener {
                    deletePreset(command)
                    true
                }
            }
        }
        binding.presetsContainer.addView(btn, binding.presetsContainer.childCount - 1)
    }

    private fun showAddPresetDialog() {
        val input = EditText(this)
        input.hint = "e.g. up --ssh"
        AlertDialog.Builder(this)
            .setTitle("New Command Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val cmd = input.text.toString().trim()
                if (cmd.isNotEmpty()) savePreset(cmd)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun savePreset(cmd: String) {
        val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.add(cmd)
        prefs.edit().putStringSet("commands", current).apply()
        loadPresets()
    }

    private fun deletePreset(cmd: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Preset")
            .setMessage("Delete '$cmd'?")
            .setPositiveButton("Yes") { _, _ ->
                val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                current.remove(cmd)
                prefs.edit().putStringSet("commands", current).apply()
                loadPresets()
            }
            .setNegativeButton("No", null).show()
    }

    private fun runCommand() {
        val cmd = binding.inputCommand.text.toString().trim()
        if (cmd.isNotEmpty()) {
            execute(cmd)
            binding.inputCommand.text.clear()
            binding.inputCommand.requestFocus()
        }
    }

    private fun execute(command: String) {
        binding.progressIndicator.isVisible = true
        appendToLog("$ tailscale $command")
        
        Thread {
            val result = try {
                Appctr.runTailscaleCmd(command)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            runOnUiThread {
                if (!isDestroyed) {
                    appendToLog(result)
                    binding.progressIndicator.isVisible = false
                    scrollToBottom()
                }
            }
        }.start()
    }

    private fun appendToLog(text: String) {
        binding.outputText.append("\n$text\n$ ")
    }
    
    private fun scrollToBottom() {
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}