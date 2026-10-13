package com.seagull.avtp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.seagull.avtp.R
import com.seagull.avtp.api.Item
import com.seagull.avtp.databinding.ItemVideoBinding

/** 原生网格 —— 封面 + 标题，点一下直接去解析播放 */
class VideoAdapter(private val onClick: (Item) -> Unit) :
    RecyclerView.Adapter<VideoAdapter.VH>() {

    private val data = ArrayList<Item>()

    fun submit(list: List<Item>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Item>) {
        val start = data.size
        data.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun clear() {
        data.clear()
        notifyDataSetChanged()
    }

    fun size() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = data[position]
        holder.b.title.text = it.title
        if (it.cover.startsWith("http")) {
            holder.b.cover.load(it.cover) { crossfade(true) }
        } else {
            holder.b.cover.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onClick(it) }
    }

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)
}
