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
    
    // История команд
    private val commandHistory = mutableListOf<String>()
    private var historyPointer = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Восстановление текста консоли
        if (historyFile.exists()) {
            try { binding.outputText.text = historyFile.readText() } catch (e: Exception) {}
            scrollToBottom()
        }

        // Обработка Enter на клавиатуре
        binding.inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand()
                true
            } else false
        }

        // Кнопки управления
        binding.btnRun.setOnClickListener { runCommand() }
        
        binding.btnClearConsole.setOnClickListener {
            binding.outputText.text = "$ "
            if (historyFile.exists()) historyFile.delete()
        }
        
        binding.btnAddPreset.setOnClickListener { showAddPresetDialog() }
        
        // --- ЛОГИКА КНОПКИ ВВЕРХ (ИСТОРИЯ) ---
        binding.btnHistoryUp.setOnClickListener {
            if (commandHistory.isNotEmpty()) {
                // Если указатель еще не активен, берем последнюю команду
                if (historyPointer == -1) {
                    historyPointer = commandHistory.size - 1
                } else if (historyPointer > 0) {
                    // Иначе двигаемся назад
                    historyPointer--
                }
                
                val cmd = commandHistory[historyPointer]
                binding.inputCommand.setText(cmd)
                binding.inputCommand.setSelection(cmd.length) // Курсор в конец строки
            }
        }

        loadPresets()
        handleIntent(intent)
        
        // Фокус при запуске
        binding.inputCommand.requestFocus()
    }

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
                intent.removeExtra("CMD")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { historyFile.writeText(binding.outputText.text.toString()) } catch (e: Exception) {}
    }

    private fun loadPresets() {
        val childCount = binding.presetsContainer.childCount
        if (childCount > 1) binding.presetsContainer.removeViews(0, childCount - 1)
        
        // Базовые пресеты
        addPresetButton("status")
        addPresetButton("netcheck")
        addPresetButton("ping 8.8.8.8")
        
        // Пользовательские пресеты
        val saved = prefs.getStringSet("commands", emptySet()) ?: emptySet()
        saved.sorted().forEach { addPresetButton(it, true) }
    }

    private fun addPresetButton(command: String, isCustom: Boolean = false) {
        val btn = Button(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_TonalButton), null, 0).apply {
            text = command
            setOnClickListener { 
                execute(command)
                binding.inputCommand.requestFocus() // Возвращаем фокус
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8 }
            if (isCustom) setOnLongClickListener { deletePreset(command); true }
        }
        binding.presetsContainer.addView(btn, binding.presetsContainer.childCount - 1)
    }

    private fun showAddPresetDialog() {
        val input = EditText(this)
        input.hint = "e.g. up --ssh"
        AlertDialog.Builder(this).setTitle("New Preset").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val cmd = input.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    current.add(cmd); prefs.edit().putStringSet("commands", current).apply(); loadPresets()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deletePreset(cmd: String) {
        AlertDialog.Builder(this).setTitle("Delete?").setMessage(cmd)
            .setPositiveButton("Yes") { _, _ ->
                val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                current.remove(cmd); prefs.edit().putStringSet("commands", current).apply(); loadPresets()
            }.setNegativeButton("No", null).show()
    }

    private fun runCommand() {
        val cmd = binding.inputCommand.text.toString().trim()
        if (cmd.isNotEmpty()) {
            // Добавляем в историю только уникальные подряд идущие команды
            if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                commandHistory.add(cmd)
            }
            historyPointer = -1 // Сброс указателя истории

            execute(cmd)
            binding.inputCommand.text.clear()
            binding.inputCommand.requestFocus()
        }
    }

    private fun execute(command: String) {
        binding.progressIndicator.isVisible = true
        binding.outputText.append("\n$ tailscale $command")
        scrollToBottom() // Скролл сразу после ввода
        
        Thread {
            val result = try { Appctr.runTailscaleCmd(command) } catch (e: Exception) { "Error: ${e.message}" }
            runOnUiThread {
                if (!isDestroyed) {
                    binding.outputText.append("\n$result\n$ ")
                    binding.progressIndicator.isVisible = false
                    
                    scrollToBottom() // Скролл после получения ответа
                    binding.inputCommand.requestFocus() // Держим фокус
                }
            }
        }.start()
    }

    private fun scrollToBottom() {
        binding.scrollView.post { 
            binding.scrollView.fullScroll(View.FOCUS_DOWN) 
        }
    }
}