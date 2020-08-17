package com.github.appmanager.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cursoradapter.widget.CursorAdapter
import com.github.appmanager.R

class CustomSuggestionsAdapter(context: Context) : CursorAdapter(context, null, 0) {
    private val inflater: LayoutInflater by lazy {
        LayoutInflater.from(context)
    }

    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View =
        inflater.inflate(R.layout.search_dropdown, parent, false)

    @SuppressLint("SetTextI18n")
    override fun bindView(view: View, context: Context, cursor: Cursor) {
        //val title = cursor.getString(1)
        val subTitle = cursor.getString(1)
        val text1 = view.findViewById<TextView>(android.R.id.text1)

        text1.text = subTitle
        val icon = view.findViewById<ImageView>(android.R.id.icon1)
        icon.setImageResource(R.drawable.ic_sentiment_satisfied)
    }
}