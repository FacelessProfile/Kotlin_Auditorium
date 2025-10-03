package com.example.kotlinroomdatabase.fragments.list

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentListBinding
import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val adapter = ListAdapter()
    private val jsonFileName = "students.json"
    @OptIn(InternalSerializationApi::class)
    private var studentList: MutableList<Student> = mutableListOf()

    @OptIn(InternalSerializationApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)

        binding.recyclerview.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerview.adapter = adapter

        loadStudents()

        adapter.setOnItemClickListener { student ->
            val bundle = Bundle()
            bundle.putInt("studentId", student.id)
            findNavController().navigate(R.id.action_listFragment_to_addFragment, bundle)
        }

        binding.floatingActionButton.setOnClickListener {
            findNavController().navigate(R.id.action_listFragment_to_addFragment)
        }

        setHasOptionsMenu(true)
        return binding.root
    }

    @OptIn(InternalSerializationApi::class)
    private fun loadStudents() {
        try {
            val file = File(requireContext().filesDir, jsonFileName)
            if (!file.exists()) {
                studentList = mutableListOf()
                adapter.setData(studentList)
                return
            }
            val jsonString = file.readText()
            val list = Json.decodeFromString<List<Student>>(jsonString)
            studentList = list.toMutableList()
            adapter.setData(studentList)
        } catch (e: Exception) {
            studentList = mutableListOf()
            adapter.setData(studentList)
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudents() {
        val jsonString = Json.encodeToString(studentList)
        requireContext().openFileOutput(jsonFileName, Context.MODE_PRIVATE).use {
            it.write(jsonString.toByteArray())
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.delete_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_delete) {
            deleteAllStudents()
        }
        return super.onOptionsItemSelected(item)
    }

    @OptIn(InternalSerializationApi::class)
    private fun deleteAllStudents() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setPositiveButton("Yes") { _, _ ->
            studentList.clear()
            saveStudents()
            adapter.setData(studentList)
            Toast.makeText(requireContext(), "Successfully removed everything", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("No") { _, _ -> }
        builder.setTitle("Delete everything ?")
        builder.setMessage("Are you sure to remove everything ?")
        builder.create().show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}