/*  Copyright (c) MediaArea.net SARL. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license that can
 *  be found in the License.html file in the root of the source tree.
 */

package net.mediaarea.mediainfo

import java.io.OutputStream

import android.support.v4.app.Fragment
import android.support.design.widget.Snackbar
import android.support.v4.provider.DocumentFile
import android.os.Bundle
import android.app.Activity
import android.content.SharedPreferences
import android.content.Context
import android.content.Intent
import android.text.Html
import android.view.*

import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

import kotlinx.android.synthetic.main.report_detail.view.*

class ReportDetailFragment : Fragment() {
    companion object {
        const val ARG_REPORT_ID: String = "id"
        const val SAVE_FILE_REQUEST_CODE: Int = 1
    }

    private val disposable: CompositeDisposable = CompositeDisposable()
    private lateinit var activityListener: ReportActivityListener
    private var sharedPreferences: SharedPreferences? = null
    private var view: String = "HTML"
    var id: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            if (it.containsKey(ARG_REPORT_ID)) {
                val newId: Int = it.getInt(ARG_REPORT_ID)
                if (newId != -1)
                    id = newId
            }
        }

        setHasOptionsMenu(true)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        try {
            activityListener = activity as ReportActivityListener
        } catch (e: ClassCastException) {
            throw ClassCastException(activity.toString() + " must implement ReportActivityListener")
        }

        sharedPreferences = activity?.getSharedPreferences(getString(R.string.preferences_key), Context.MODE_PRIVATE)

        sharedPreferences?.getString(getString(R.string.preferences_view_key), "HTML").let {
            if (it != null)
                view = it
        }
    }

    override fun onStop() {
        super.onStop()

        // clear all the subscription
        disposable.clear()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val rootView = inflater.inflate(R.layout.report_detail, container, false)
        // Show the report
        id?.let {
            disposable.add(activityListener.getReportViewModel().getReport(it)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        activity?.title = it.filename

                        val report: String = Core.convertReport(it.report, view)
                        var content: String = ""
                        if (view != "HTML") {
                            content += "<html><body><pre>"
                            content += Html.escapeHtml(report.replace("\t", "    "))
                            content += "</pre></body></html>"
                        } else {
                            content+=report
                        }

                        rootView.report_detail.loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
             }))
        }

        return rootView
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_detail, menu)

        menu.findItem(R.id.action_export_report).let {
            it.setOnMenuItemClickListener {
                val intent: Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, SAVE_FILE_REQUEST_CODE)

                true
            }
        }

        val viewMenu: SubMenu = menu.findItem(R.id.action_change_view).subMenu

        for (current: Core.ReportView in Core.views) {
            val index: Int = Core.views.indexOf(current)
            viewMenu.add(R.id.menu_views_group, Menu.NONE, index, current.desc).setOnMenuItemClickListener { item: MenuItem ->
                val requested: String = Core.views.findLast { it.desc == item.title }?.name.orEmpty()

                if (requested.isNotEmpty() && !requested.contentEquals(view)) {
                    view = requested

                    // Save new default
                    sharedPreferences
                            ?.edit()
                            ?.putString(getString(R.string.preferences_view_key), view)
                            ?.apply()

                    // Reset view
                    fragmentManager
                            ?.beginTransaction()
                            ?.detach(this)
                            ?.attach(this)
                            ?.commit()
                }

                true
            }.setCheckable(true).setChecked(current.name == view)

            viewMenu.setGroupCheckable(R.id.menu_views_group, true, true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                SAVE_FILE_REQUEST_CODE -> {
                    if (resultData == null) {
                        onError()
                        return
                    }

                    id.let {
                        if (it == null) {
                            onError()
                            return
                        }

                        disposable
                                .add(activityListener.getReportViewModel().getReport(it)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe {
                                    if (it.report.isEmpty()) {
                                        onError()
                                    } else {
                                        val directory: DocumentFile = DocumentFile.fromTreeUri(context, resultData.data)

                                       if (!directory.canWrite()) {
                                            onError()
                                        } else {
                                            val reportText: String = Core.convertReport(it.report, view, true)
                                            val filename: String = String.format("%s.%s", it.filename, view)
                                            val mime: String = Core.views.find { it.name == view }?.mime ?: "text/plain"

                                            try {
                                                val document: DocumentFile = directory.createFile(mime, filename)
                                                val ostream: OutputStream? = context?.contentResolver?.openOutputStream(document.uri)

                                                if (ostream == null) {
                                                    onError()
                                                } else {
                                                    ostream.write(reportText.toByteArray())
                                                    ostream.flush()
                                                    ostream.close()
                                                }
                                            } catch (e: Exception) {
                                                onError()
                                            }
                                        }
                                    }
                                })
                    }
                }
            }
        }
    }

    private fun onError() {
        val rootView: View? = getView()

        // Show error message for 5 seconds
        if (rootView != null) {
            Snackbar.make(rootView, R.string.error_text, 5000)
                    .setAction("Action", null)
                    .show()
        }
    }
}
