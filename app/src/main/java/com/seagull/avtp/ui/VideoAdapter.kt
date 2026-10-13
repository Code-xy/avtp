package com.seagull.avtp.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.seagull.avtp.api.Item
import com.seagull.avtp.databinding.ItemVideoBinding

/** 原生网格 —— 封面 + 标题，点一下直接去解析播放 */
class VideoAdapter(private val onClick: (Item) -> Unit) :
    RecyclerView.Adapter<VideoAdapter.VH>() {

    private val data = ArrayList<Item>()

    /** 无封面时的占位底色，按标题 hash 取，避免整屏一个色 */
    private val palette = intArrayOf(
        0xFF2A3340.toInt(), 0xFF3A2E3F.toInt(), 0xFF243A36.toInt(),
        0xFF3B3327.toInt(), 0xFF2C2F45.toInt(), 0xFF3A2A2A.toInt(),
    )

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
        val item = data[position]
        holder.b.title.text = item.title

        if (item.cover.startsWith("http")) {
            holder.b.cover.visibility = View.VISIBLE
            holder.b.coverFallback.visibility = View.GONE
            holder.b.cover.load(item.cover) { crossfade(true) }
        } else {
            // 优酷接口不返回封面图 —— 用设计化占位，别留一块死灰
            holder.b.cover.visibility = View.GONE
            holder.b.coverFallback.visibility = View.VISIBLE
            holder.b.coverFallback.text = item.title.take(10)
            holder.b.coverFallback.setBackgroundColor(
                palette[(item.title.hashCode() and 0x7fffffff) % palette.size]
            )
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)
}
