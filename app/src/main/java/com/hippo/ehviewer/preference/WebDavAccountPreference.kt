package com.hippo.ehviewer.preference

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.preference.DialogPreference
import com.hippo.yorozuya.ViewUtils

class WebDavAccountPreference(
    context: Context,
    attrs: AttributeSet? = null,
) : DialogPreference(context, attrs),
    View.OnClickListener {

    private var mServerLayout: TextInputLayout? = null
    private var mServerUrl: EditText? = null
    private var mUserLayout: TextInputLayout? = null
    private var mUsername: EditText? = null
    private var mPasswordLayout: TextInputLayout? = null
    private var mPassword: EditText? = null

    init {
        dialogLayoutResource = R.layout.preference_dialog_webdav_account
        updateSummary()
    }

    private fun updateSummary() {
        val server = Settings.webDavServer
        summary = if (server.isBlank()) {
            context.getString(R.string.settings_webdav_account_not_configured)
        } else {
            val user = Settings.webDavUser
            if (user.isBlank()) {
                server
            } else {
                "$user @ $server"
            }
        }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.ok, null)
    }

    @SuppressLint("SetTextI18n")
    override fun onDialogCreated(dialog: AlertDialog) {
        super.onDialogCreated(dialog)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(this)
        mServerLayout = ViewUtils.`$$`(dialog, R.id.server_input_layout) as TextInputLayout
        mServerUrl = ViewUtils.`$$`(dialog, R.id.server_url) as EditText
        mUserLayout = ViewUtils.`$$`(dialog, R.id.user_input_layout) as TextInputLayout
        mUsername = ViewUtils.`$$`(dialog, R.id.username) as EditText
        mPasswordLayout = ViewUtils.`$$`(dialog, R.id.password_input_layout) as TextInputLayout
        mPassword = ViewUtils.`$$`(dialog, R.id.password) as EditText

        mServerUrl!!.setText(Settings.webDavServer)
        mUsername!!.setText(Settings.webDavUser)
        mPassword!!.setText(Settings.webDavPassword)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        mServerLayout = null
        mServerUrl = null
        mUserLayout = null
        mUsername = null
        mPasswordLayout = null
        mPassword = null
    }

    override fun onClick(v: View) {
        val dialog = dialog
        if (dialog == null || mServerUrl == null || mUsername == null || mPassword == null) {
            return
        }

        val server = mServerUrl!!.text.toString().trim()
        val user = mUsername!!.text.toString().trim()
        val password = mPassword!!.text.toString()

        if (server.isNotBlank() && !server.startsWith("http://") && !server.startsWith("https://")) {
            mServerLayout?.error = context.getString(R.string.settings_webdav_invalid_url)
            return
        }
        mServerLayout?.error = null

        Settings.putWebDavServer(server)
        Settings.putWebDavUser(user)
        Settings.putWebDavPassword(password)
        updateSummary()
        dialog.dismiss()
    }
}
