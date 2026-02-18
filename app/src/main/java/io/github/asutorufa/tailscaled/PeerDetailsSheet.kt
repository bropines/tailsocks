package io.github.asutorufa.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PeerDetailsSheet(private val peer: PeerData) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.layout_peer_details, container, false)
        val containerList = root.findViewById<LinearLayout>(R.id.details_container)
        
        root.findViewById<TextView>(R.id.title).text = peer.getDisplayName()
        
        // Кнопка Ping внутри шторки
        root.findViewById<Button>(R.id.btn_ping).setOnClickListener {
             val intent = Intent(requireContext(), ConsoleActivity::class.java).apply {
                putExtra("CMD", "ping ${peer.getPrimaryIp()}")
            }
            startActivity(intent)
            dismiss()
        }

        // Генерируем список
        peer.getDetailsList().forEach { (label, value) ->
            val row = inflater.inflate(R.layout.item_peer_detail_row, containerList, false)
            row.findViewById<TextView>(R.id.label).text = label
            row.findViewById<TextView>(R.id.value).text = value
            
            // Копирование по клику
            row.setOnClickListener {
                val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
            }
            containerList.addView(row)
        }
        return root
    }
}