package com.flip5.dockpilot

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : RecyclerView.Adapter<LogAdapter.EntryHolder>() {

    private var items: List<AppLog.Entry> = emptyList()

    fun submit(entries: List<AppLog.Entry>) {
        items = entries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return EntryHolder(view)
    }

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val time: TextView = view.findViewById(R.id.tvLogTime)
        private val message: TextView = view.findViewById(R.id.tvLogMessage)

        fun bind(entry: AppLog.Entry) {
            time.text = entry.time
            message.text = entry.message
            message.setTextColor(
                when (entry.level) {
                    AppLog.Level.INFO -> Color.parseColor("#263238")
                    AppLog.Level.WARN -> Color.parseColor("#EF6C00")
                    AppLog.Level.ERROR -> Color.parseColor("#C62828")
                }
            )
        }
    }
}
