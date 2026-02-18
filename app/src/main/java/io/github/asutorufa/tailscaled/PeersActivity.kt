package io.github.asutorufa.tailscaled

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
                // Важно: флаг чтобы не плодить окна консоли
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        },
        onDetailsClick = { peer ->
            showPeerDetails(peer)
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

    private fun showPeerDetails(peer: PeerData) {
        val details = peer.getFullDetails()
        
        AlertDialog.Builder(this)
            .setTitle(peer.getDisplayName())
            .setMessage(details)
            .setPositiveButton("Copy All") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Peer Details", details))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadPeers() {
        binding.swipeRefresh.isRefreshing = true
        binding.errorText.visibility = View.GONE

        Thread {
            try {
                val jsonOutput = Appctr.runTailscaleCmd("status --json")
                val status = gson.fromJson(jsonOutput, StatusResponse::class.java)
                
                // ВАЖНО: берем peers (Map), превращаем в List
                val peersList = status.peers?.values?.toList() ?: emptyList()
                
                val sortedList = peersList.sortedWith(
                    compareByDescending<PeerData> { it.isOnline() }
                        .thenBy { it.getDisplayName() }
                )

                runOnUiThread {
                    if (!isDestroyed) {
                        adapter.submitList(sortedList)
                        binding.swipeRefresh.isRefreshing = false
                        if (sortedList.isEmpty()) {
                            binding.errorText.text = "No peers found or service offline."
                            binding.errorText.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        binding.swipeRefresh.isRefreshing = false
                        binding.errorText.text = "Error: ${e.message}"
                        binding.errorText.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }
}