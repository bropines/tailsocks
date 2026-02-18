package io.github.asutorufa.tailscaled

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import appctr.Appctr
import com.google.gson.Gson
import io.github.asutorufa.tailscaled.databinding.ActivityPeersBinding

class PeersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPeersBinding
    private val adapter = PeersAdapter { ip ->
        // Open Console and run ping
        val intent = Intent(this, ConsoleActivity::class.java)
        intent.putExtra("CMD", "ping $ip")
        startActivity(intent)
    }
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            loadPeers()
        }

        loadPeers()
    }

    private fun loadPeers() {
        binding.swipeRefresh.isRefreshing = true
        binding.errorText.visibility = View.GONE

        Thread {
            try {
                // 1. Run command
                val jsonOutput = Appctr.runTailscaleCmd("status --json")
                
                // 2. Parse
                val status = gson.fromJson(jsonOutput, StatusResponse::class.java)
                
                // 3. Convert Map to List & Sort (Online first, then by name)
                val peersList = status.peers?.values?.toList() ?: emptyList()
                val sortedList = peersList.sortedWith(
                    compareByDescending<PeerData> { it.isOnline() }
                        .thenBy { it.hostName }
                )

                runOnUiThread {
                    if (!isDestroyed) {
                        adapter.submitList(sortedList)
                        binding.swipeRefresh.isRefreshing = false
                        
                        if (sortedList.isEmpty()) {
                            binding.errorText.text = "No peers found or Tailscale is stopped."
                            binding.errorText.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        binding.swipeRefresh.isRefreshing = false
                        binding.errorText.text = "Error parsing status: ${e.message}"
                        binding.errorText.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }
}