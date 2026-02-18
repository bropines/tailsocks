package io.github.asutorufa.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.asutorufa.tailscaled.databinding.ItemPeerBinding

class PeersAdapter(private val onPingClick: (String) -> Unit) : 
    ListAdapter<PeerData, PeersAdapter.PeerViewHolder>(PeerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeerViewHolder(binding, onPingClick)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PeerViewHolder(
        private val binding: ItemPeerBinding,
        private val onPingClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(peer: PeerData) {
            binding.peerName.text = peer.hostName
            binding.peerIp.text = peer.getPrimaryIp()
            
            // OS Badge
            binding.peerOs.text = peer.os?.take(2)?.uppercase() ?: "??"
            
            // Online Status
            val isOnline = peer.isOnline()
            val color = if (isOnline) Color.GREEN else Color.GRAY
            binding.statusDot.setBackgroundColor(color)

            binding.btnCopyIp.setOnClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Tailscale IP", peer.getPrimaryIp())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(itemView.context, "IP Copied", Toast.LENGTH_SHORT).show()
            }

            binding.btnPingPeer.setOnClickListener {
                onPingClick(peer.getPrimaryIp())
            }
        }
    }

    class PeerDiffCallback : DiffUtil.ItemCallback<PeerData>() {
        override fun areItemsTheSame(oldItem: PeerData, newItem: PeerData) = oldItem.hostName == newItem.hostName
        override fun areContentsTheSame(oldItem: PeerData, newItem: PeerData) = oldItem == newItem
    }
}