package com.github.kr328.clash.design.component

import android.content.Context
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.ConnectionAggregateSort

class ConnectionSortMenu(
    context: Context,
    anchor: View,
    initial: ConnectionAggregateSort,
    private val onSortChanged: (ConnectionAggregateSort) -> Unit,
) : PopupMenu.OnMenuItemClickListener {
    private val popup = PopupMenu(context, anchor)

    fun show() {
        popup.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val sort = when (item.itemId) {
            R.id.connection_sort_bytes -> ConnectionAggregateSort.Bytes
            R.id.connection_sort_count -> ConnectionAggregateSort.Count
            else -> return false
        }
        item.isChecked = true
        onSortChanged(sort)
        return true
    }

    init {
        popup.menuInflater.inflate(R.menu.menu_connection_sort, popup.menu)
        popup.menu.findItem(
            when (initial) {
                ConnectionAggregateSort.Bytes -> R.id.connection_sort_bytes
                ConnectionAggregateSort.Count -> R.id.connection_sort_count
            },
        ).isChecked = true
        popup.setOnMenuItemClickListener(this)
    }
}
