package io.github.asutorufa.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import appctr.Appctr
import io.github.asutorufa.tailscaled.databinding.ActivityLogsBinding

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val adapter = LogsAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var isAutoScroll = true

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed && !isFinishing) {
                loadLogs(isAutoRefresh = true)
                handler.postDelayed(this, 2000)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        // Отключаем анимацию для логов, чтобы не мерцало при обновлении
        binding.recyclerView.itemAnimator = null 

        binding.recyclerView.setOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy < 0) isAutoScroll = false
                if (!recyclerView.canScrollVertically(1)) isAutoScroll = true
            }
        })

        binding.swipeRefresh.setOnRefreshListener { 
            loadLogs() 
            isAutoScroll = true
        }

        binding.fabClear.setOnClickListener {
            clearLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.logs_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy -> {
                copyLogs()
                true
            }
            R.id.action_share -> {
                shareLogs()
                true
            }
            R.id.action_clear -> {
                clearLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadLogs(isAutoRefresh: Boolean = false) {
        if (!isAutoRefresh) binding.swipeRefresh.isRefreshing = true
        
        Thread {
            val logsString = try { Appctr.getLogs() } catch (e: Exception) { "" }
            
            val logsList = if (logsString.isEmpty()) {
                emptyList()
            } else {
                logsString.split("\n").filter { it.isNotEmpty() }
            }
            
            runOnUiThread {
                adapter.submitList(logsList) {
                     if (isAutoScroll && logsList.isNotEmpty()) {
                         binding.recyclerView.scrollToPosition(logsList.size - 1)
                     }
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }.start()
    }

    private fun clearLogs() {
        Appctr.clearLogs()
        adapter.submitList(emptyList()) // Мгновенно очищаем список в UI
        Toast.makeText(this, getString(R.string.clear_logs), Toast.LENGTH_SHORT).show()
    }

    private fun copyLogs() {
        val logs = Appctr.getLogs()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Tailscale Logs", logs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val logs = Appctr.getLogs()
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, logs)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share logs via"))
    }
}