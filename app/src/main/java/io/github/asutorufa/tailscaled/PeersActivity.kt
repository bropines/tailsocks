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
    private val gson = Gson()
    
    private val adapter = PeersAdapter(
        onPingClick = { ip ->
            val intent = Intent(this, ConsoleActivity::class.java).apply {
                putExtra("CMD", "ping $ip")
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        },
        onDetailsClick = { peer ->
            val sheet = PeerDetailsSheet(peer)
            sheet.show(supportFragmentManager, "peer_details")
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadPeers() }
        loadPeers()
    }

    private fun loadPeers() {
        binding.swipeRefresh.isRefreshing = true
        binding.errorText.visibility = View.GONE

        Thread {
            try {
                val jsonOutput = Appctr.runTailscaleCmd("status --json")
                
                if (jsonOutput.isEmpty() || jsonOutput.startsWith("Error")) {
                    throw Exception("Daemon not running or returned error")
                }

                val status = gson.fromJson(jsonOutput, StatusResponse::class.java)
                val peersList = status.peers?.values?.toList() ?: emptyList()
                
                val sortedList = peersList.sortedWith(
                    compareByDescending<PeerData> { it.online == true }
                        .thenBy { it.getDisplayName() }
                )

                runOnUiThread {
                    if (!isDestroyed) {
                        adapter.submitList(sortedList)
                        binding.swipeRefresh.isRefreshing = false
                        if (sortedList.isEmpty()) {
                            binding.errorText.text = "No peers found."
                            binding.errorText.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        binding.swipeRefresh.isRefreshing = false
                        binding.errorText.text = "Error: ${e.message}\nMake sure Tailscale is running."
                        binding.errorText.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }
}