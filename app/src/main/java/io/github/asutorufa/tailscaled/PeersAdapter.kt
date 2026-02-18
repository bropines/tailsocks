package io.github.asutorufa.tailscaled

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.asutorufa.tailscaled.databinding.ItemPeerBinding

class PeersAdapter(
    private val onPingClick: (String) -> Unit,
    private val onDetailsClick: (PeerData) -> Unit
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
            // Имя
            binding.peerName.text = peer.getDisplayName()
            
            // IP и DNS
            if (peer.hostName != peer.getDisplayName()) {
                binding.peerIp.text = "${peer.getPrimaryIp()} • ${peer.hostName}"
            } else {
                binding.peerIp.text = peer.getPrimaryIp()
            }
            
            // OS Icon Text
            binding.peerOs.text = peer.os?.take(2)?.uppercase() ?: "??"
            
            // --- ФИКС ИНДИКАТОРА ---
            // Если online == null или false -> Серый. Только если true -> Зеленый.
            val isOnline = peer.online == true 
            
            val statusColor = if (isOnline) {
                 Color.parseColor("#4CAF50") // Яркий зеленый
            } else {
                 Color.parseColor("#9E9E9E") // Серый (Material Grey 500)
            }
            binding.statusDot.setBackgroundColor(statusColor)

            // Клик по всей карточке -> Детали
            binding.root.setOnClickListener { onDetailsClick(peer) }
        }
    }

    class PeerDiffCallback : DiffUtil.ItemCallback<PeerData>() {
        override fun areItemsTheSame(oldItem: PeerData, newItem: PeerData) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PeerData, newItem: PeerData) = oldItem == newItem
    }
}