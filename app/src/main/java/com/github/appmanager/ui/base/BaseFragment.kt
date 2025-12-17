package com.github.appmanager.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import androidx.fragment.app.Fragment
import java.lang.reflect.ParameterizedType


abstract class BaseFragment<VB : ViewBinding>() : Fragment() {
    lateinit var binding: VB
    open fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB {
        val type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
        val clazz = (type as Class<*>)
        val method = clazz.getMethod(
            "inflate",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType
        )
        @Suppress("UNCHECKED_CAST") return method.invoke(null, inflater, container, false) as VB
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = getViewBinding(inflater, container)
        return binding.root
    }
}