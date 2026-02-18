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

class PeersAdapter(
    private val onPingClick: (String) -> Unit,
    private val onDetailsClick: (PeerData) -> Unit // Новый колбэк
) : ListAdapter<PeerData, PeersAdapter.PeerViewHolder>(PeerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeerViewHolder(binding, onPingClick, onDetailsClick)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PeerViewHolder(
        private val binding: ItemPeerBinding,
        private val onPingClick: (String) -> Unit,
        private val onDetailsClick: (PeerData) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(peer: PeerData) {
            // Отображаем Имя из веб-панели
            binding.peerName.text = peer.getDisplayName()
            
            // Если имя хоста отличается, пишем его мелко рядом (или в IP поле)
            if (peer.hostName != peer.getDisplayName()) {
                binding.peerIp.text = "${peer.getPrimaryIp()} • ${peer.hostName}"
            } else {
                binding.peerIp.text = peer.getPrimaryIp()
            }
            
            binding.peerOs.text = peer.os?.take(2)?.uppercase() ?: "??"
            
            val color = if (peer.isOnline()) Color.parseColor("#4CAF50") else Color.GRAY
            binding.statusDot.setBackgroundColor(color)

            // Клик по всей карте - детали
            binding.root.setOnClickListener { onDetailsClick(peer) }

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
        override fun areItemsTheSame(oldItem: PeerData, newItem: PeerData) = oldItem.getDisplayName() == newItem.getDisplayName()
        override fun areContentsTheSame(oldItem: PeerData, newItem: PeerData) = oldItem == newItem
    }
}