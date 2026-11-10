package com.example.posewear

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThumbAdapter(private val onClick: (LibItem) -> Unit) :
    RecyclerView.Adapter<ThumbAdapter.VH>() {

    val items = mutableListOf<LibItem>()
    var selectedId: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.item_root)
        val iv: ImageView = v.findViewById(R.id.iv_thumb)
        val label: TextView = v.findViewById(R.id.tv_label)
        var job: Job? = null
    }

    fun set(list: List<LibItem>, selected: String = selectedId) {
        items.clear()
        items.addAll(list)
        selectedId = selected
        notifyDataSetChanged()
    }

    fun setSelected(id: String) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.label.text = item.name
        h.root.setBackgroundResource(
            if (item.id == selectedId) R.drawable.bg_thumb_selected else R.drawable.bg_thumb
        )
        h.root.setOnClickListener { onClick(item) }
        h.iv.setImageBitmap(null)
        h.iv.tag = item.locator
        h.job?.cancel()
        h.job = scope.launch {
            val bmp = Images.thumbnail(h.iv.context, item.locator, 256)
            if (bmp != null) withContext(Dispatchers.Main) {
                if (h.iv.tag == item.locator && h.bindingAdapterPosition != RecyclerView.NO_POSITION)
                    h.iv.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(h: VH) {
        h.job?.cancel()
        h.job = null
        h.iv.setImageBitmap(null)
    }
}
