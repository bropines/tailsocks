package io.github.asutorufa.tailscaled

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import appctr.Appctr
import io.github.asutorufa.tailscaled.databinding.ActivityConsoleBinding

class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Execute on Enter key
        binding.inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand()
                true
            } else {
                false
            }
        }

        binding.btnRun.setOnClickListener {
            runCommand()
        }

        // Quick Actions
        binding.btnStatus.setOnClickListener { execute("status") }
        binding.btnPing.setOnClickListener { execute("ping -c 4 8.8.8.8") }
        binding.btnNetcheck.setOnClickListener { execute("netcheck") }
        binding.btnHelp.setOnClickListener { execute("--help") }
    }

    private fun runCommand() {
        val cmd = binding.inputCommand.text.toString().trim()
        if (cmd.isNotEmpty()) {
            execute(cmd)
        }
    }

    private fun execute(command: String) {
        binding.inputCommand.setText(command)
        binding.inputCommand.setSelection(command.length)
        
        binding.progressIndicator.isVisible = true
        binding.outputText.text = "Executing: tailscale $command..."
        binding.btnRun.isEnabled = false

        Thread {
            val result = try {
                // Вызываем Go-функцию. Убедись, что appctr обновился!
                Appctr.runTailscaleCmd(command)
            } catch (e: Exception) {
                "Error executing command: ${e.message}"
            }

            runOnUiThread {
                if (!isDestroyed) {
                    binding.outputText.text = result
                    binding.progressIndicator.isVisible = false
                    binding.btnRun.isEnabled = true
                }
            }
        }.start()
    }
}