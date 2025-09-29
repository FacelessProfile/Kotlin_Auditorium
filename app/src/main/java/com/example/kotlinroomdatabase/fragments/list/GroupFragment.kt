package com.example.kotlinroomdatabase.fragments.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.kotlinroomdatabase.GroupViewModel
import com.example.kotlinroomdatabase.R

class GroupFragment : Fragment() {

    companion object {
        fun newInstance() = GroupFragment()
    }

    private val viewModel: GroupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: Use the ViewModel
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_group, container, false)
    }
}