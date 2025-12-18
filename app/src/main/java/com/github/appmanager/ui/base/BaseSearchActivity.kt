package com.github.appmanager.ui.base

import android.app.SearchManager
import android.database.MatrixCursor
import android.provider.BaseColumns
import android.view.Menu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.MutableLiveData
import com.github.appmanager.R
import com.github.appmanager.adapter.CustomSuggestionsAdapter
import java.util.Locale.getDefault

abstract class BaseSearchActivity : BaseActivity() {
    var searchView: SearchView? = null
    val allDataList = mutableListOf<Triple<String, String, String>>()
    var onClickItem = MutableLiveData<String?>()
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.search, menu)
        searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView?.apply {
            searchView?.queryHint = getString(R.string.search_view_hint)
            searchView?.setIconifiedByDefault(true)
            searchView?.suggestionsAdapter =
                CustomSuggestionsAdapter(this@BaseSearchActivity)
            searchView?.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
                override fun onSuggestionSelect(position: Int): Boolean = false

                override fun onSuggestionClick(position: Int): Boolean {
                    val info = with(searchView?.suggestionsAdapter?.cursor) {
                        this?.moveToPosition(position)
                        this?.getString(3)
                    }
                    onClickItem.value = info
                    return true
                }
            })
            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(query: String): Boolean {
                    val newCursor = MatrixCursor(
                        arrayOf(
                            BaseColumns._ID,
                            SearchManager.SUGGEST_COLUMN_TEXT_1,
                            SearchManager.SUGGEST_COLUMN_TEXT_2,
                            SearchManager.SUGGEST_COLUMN_TEXT_2_URL
                        )
                    )
                    allDataList.filter {
                        it.second.lowercase(getDefault()).contains(
                            query.lowercase(
                                getDefault()
                            )
                        )
                    }
                        .forEachIndexed { index, any ->
                            newCursor.addRow(arrayOf(index, any.first, any.second, any.third))
                        }
                    searchView?.suggestionsAdapter?.swapCursor(newCursor)
                    return false
                }
            })
        }
        return true
    }
}