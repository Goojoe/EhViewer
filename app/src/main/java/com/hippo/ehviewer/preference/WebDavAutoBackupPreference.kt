package com.hippo.ehviewer.preference

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.backup.WebDavBackupManager
import com.hippo.preference.DialogPreference
import com.hippo.yorozuya.ViewUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WebDavAutoBackupPreference(
    context: Context,
    attrs: AttributeSet? = null,
) : DialogPreference(context, attrs) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var hoursView: EditText? = null

    init {
        dialogLayoutResource = R.layout.preference_dialog_webdav_auto_backup
        updateSummary()
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setNegativeButton(R.string.settings_webdav_auto_backup_test, null)
        builder.setPositiveButton(android.R.string.ok, null)
    }

    override fun onCreateDialogView() = super.onCreateDialogView()?.also { view ->
        hoursView = ViewUtils.`$$`(view, R.id.webdav_auto_backup_hours) as EditText
    }

    override fun onDialogCreated(dialog: AlertDialog) {
        super.onDialogCreated(dialog)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { startTest() }
        hoursView?.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.webDavAutoBackupInterval.takeIf { it > 0 }?.toString().orEmpty())
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val hours = hoursView?.text?.toString()?.toLongOrNull()
            if (hours == null || hours < 0) {
                hoursView?.error = context.getString(R.string.settings_webdav_auto_backup_invalid)
                return@setOnClickListener
            }
            Settings.putWebDavAutoBackupInterval(hours)
            updateSummary()
            dialog.dismiss()
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        hoursView = null
    }

    private fun startTest() {
        if (Settings.webDavServer.isBlank()) {
            Toast.makeText(context, R.string.webdav_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.settings_webdav_auto_backup_test_started, Toast.LENGTH_SHORT).show()
        scope.launch {
            WebDavBackupManager.uploadBackup(context.applicationContext, isAuto = false)
        }
    }

    private fun updateSummary() {
        val hours = Settings.webDavAutoBackupInterval
        summary = if (hours <= 0) {
            context.getString(R.string.settings_webdav_auto_backup_disabled)
        } else {
            context.getString(R.string.settings_webdav_auto_backup_every_hours, hours)
        }
    }
}
