package com.hippo.ehviewer.preference

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.backup.WebDavBackupManager
import com.hippo.preference.DialogPreference
import com.hippo.yorozuya.ViewUtils

/**
 * Exports the WebDAV settings as a Base64 encoded payload and copies it to the clipboard.
 *
 * The payload keeps the password in plain text inside the JSON, so the dialog states
 * explicitly that the text must be handled as a secret.
 */
class WebDavConfigExportPreference(
    context: Context,
    attrs: AttributeSet? = null,
) : DialogPreference(context, attrs),
    View.OnClickListener {

    private var mPayloadView: EditText? = null
    private var mCopyView: TextView? = null
    private var mPayload: String = ""

    init {
        dialogLayoutResource = R.layout.preference_dialog_webdav_config
        summary = context.getString(R.string.settings_webdav_config_export_summary)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.cancel, null)
    }

    override fun onCreateDialogView(): View? = super.onCreateDialogView()?.also {
        mPayloadView = ViewUtils.`$$`(it, R.id.webdav_config_payload) as EditText
        mCopyView = ViewUtils.`$$`(it, R.id.webdav_config_copy) as TextView
    }

    override fun onDialogCreated(dialog: AlertDialog) {
        super.onDialogCreated(dialog)
        val payloadView = mPayloadView ?: return
        if (Settings.webDavServer.isBlank()) {
            dialog.dismiss()
            Toast.makeText(context, R.string.settings_webdav_config_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        mPayload = WebDavBackupManager.exportConfig()
        payloadView.setText(mPayload)
        payloadView.setSelection(0)
        mCopyView?.apply {
            visibility = View.VISIBLE
            setOnClickListener(this@WebDavConfigExportPreference)
        }
        copyToClipboard(mPayload)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        mPayloadView = null
        mCopyView = null
        mPayload = ""
    }

    override fun onClick(v: View) {
        copyToClipboard(mPayload)
    }

    private fun copyToClipboard(text: String): Boolean = try {
        val manager = context.getSystemService<ClipboardManager>() ?: return false
        val label = context.getString(R.string.settings_webdav_config_clip_label)
        val clip = ClipData.newPlainText(label, text)
        // The system shows its own confirmation on Android 13+, so no toast there.
        manager.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.settings_webdav_config_copied, Toast.LENGTH_SHORT).show()
        }
        true
    } catch (_: Exception) {
        false
    }
}
