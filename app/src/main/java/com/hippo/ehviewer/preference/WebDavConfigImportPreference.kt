package com.hippo.ehviewer.preference

import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import com.hippo.ehviewer.R
import com.hippo.ehviewer.backup.WebDavBackupManager
import com.hippo.preference.DialogPreference
import com.hippo.yorozuya.ViewUtils

/**
 * Imports the WebDAV settings from a Base64 payload produced by [WebDavConfigExportPreference].
 *
 * The settings are only written after the payload decoded successfully, a failed import
 * never leaves the account in a half-updated state.
 */
class WebDavConfigImportPreference(
    context: Context,
    attrs: AttributeSet? = null,
) : DialogPreference(context, attrs),
    View.OnClickListener {

    private lateinit var mDialog: AlertDialog
    private var mPayloadView: EditText? = null
    private var mPasteView: TextView? = null

    init {
        dialogLayoutResource = R.layout.preference_dialog_webdav_config
        summary = context.getString(R.string.settings_webdav_config_import_summary)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.ok, null)
    }

    override fun onCreateDialogView(): View? = super.onCreateDialogView()?.also {
        mPayloadView = ViewUtils.`$$`(it, R.id.webdav_config_payload) as EditText
        mPasteView = ViewUtils.`$$`(it, R.id.webdav_config_paste) as TextView
    }

    override fun onDialogCreated(dialog: AlertDialog) {
        super.onDialogCreated(dialog)
        mDialog = dialog
        mPayloadView?.hint = context.getString(R.string.settings_webdav_config_import_hint)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(this)
        mPasteView?.apply {
            visibility = View.VISIBLE
            setOnClickListener(this@WebDavConfigImportPreference)
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        mPayloadView = null
        mPasteView = null
    }

    override fun onClick(v: View) {
        if (v === mPasteView) {
            pasteFromClipboard()
            return
        }
        val input = mPayloadView?.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            showError(R.string.settings_webdav_config_import_empty)
            return
        }
        val config = WebDavBackupManager.importConfig(input)
        if (config == null) {
            showError(R.string.settings_webdav_config_import_invalid)
            return
        }
        val success = context.getString(R.string.settings_webdav_config_import_success, config.server, config.path)
        Toast.makeText(context, success, Toast.LENGTH_LONG).show()
        mDialog.dismiss()
    }

    private fun pasteFromClipboard() {
        val manager = context.getSystemService<ClipboardManager>() ?: return
        val text = manager.primaryClip?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.settings_webdav_config_clip_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val payloadView = mPayloadView ?: return
        payloadView.setText(text.trim())
        payloadView.setSelection(payloadView.text?.length ?: 0)
    }

    private fun showError(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
