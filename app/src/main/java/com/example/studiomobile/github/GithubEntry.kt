package com.example.studiomobile.github

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.studiomobile.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException

object GithubEntry {
    private const val TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new"

    @JvmStatic
    fun open(activity: Activity) {
        if (GithubTokenStore.has(activity)) start(activity) else askToken(activity)
    }

    private fun start(activity: Activity) {
        activity.startActivity(Intent(activity, GithubBuildActivity::class.java))
    }

    private fun askToken(activity: Activity) {
        val views = GithubViews(activity)
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(views.dp(24), views.dp(8), views.dp(24), 0)
        content.addView(
            views.text(
                "Enter a GitHub fine-grained personal access token for the repositories you want to build. " +
                    "Give it these repository permissions: Actions (read and write) and Contents (read). " +
                    "The token is saved encrypted on this device.",
                14f,
                R.color.cosmic_muted
            )
        )
        val field = EditText(activity)
        field.hint = "github_pat_..."
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.setSingleLine(true)
        content.addView(field, views.params(top = 12))
        val status = TextView(activity)
        status.setTextColor(views.res(R.color.cosmic_danger))
        status.visibility = View.GONE
        content.addView(status, views.params(top = 8))

        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("GitHub token")
            .setView(content)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Create token", null)
            .create()
        dialog.setCancelable(false)
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_PAGE)))
        }
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val token = field.text.toString().trim()
            if (token.isEmpty()) {
                status.text = "Paste your token first."
                status.visibility = View.VISIBLE
                return@setOnClickListener
            }
            save.isEnabled = false
            field.isEnabled = false
            status.setTextColor(views.res(R.color.cosmic_muted))
            status.text = "Checking the token..."
            status.visibility = View.VISIBLE
            Thread {
                var error = ""
                try {
                    GithubApi(token).user()
                    GithubTokenStore.save(activity, token)
                } catch (e: GithubApiException) {
                    error = e.message ?: "The token was rejected."
                } catch (e: IOException) {
                    error = "Connection problem. Check your internet and try again."
                } catch (e: Exception) {
                    error = "The token could not be saved."
                }
                activity.runOnUiThread {
                    if (error.isEmpty()) {
                        dialog.dismiss()
                        start(activity)
                    } else {
                        status.setTextColor(views.res(R.color.cosmic_danger))
                        status.text = error
                        save.isEnabled = true
                        field.isEnabled = true
                    }
                }
            }.start()
        }
    }
}
