package com.sardinetracker.sync

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run (and later "Settings") screen: collects the server URL, bearer
 * token, and account id, then stores them encrypted via [SecureConfig]. This is
 * the Android equivalent of the iOS app's Keychain setup — it's why the token
 * never has to live in source. Pre-fills from existing settings when reopened.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val urlField = findViewById<EditText>(R.id.serverUrl)
        val tokenField = findViewById<EditText>(R.id.bearerToken)
        val userIdField = findViewById<EditText>(R.id.userId)

        // Pre-fill when editing existing settings.
        SecureConfig.load(this)?.let {
            urlField.setText(it.serverUrl)
            tokenField.setText(it.token)
            userIdField.setText(it.userId.toString())
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val url = urlField.text.toString().trim()
            val token = tokenField.text.toString().trim()
            val userId = userIdField.text.toString().trim().toIntOrNull() ?: 0

            when {
                !url.startsWith("http://") && !url.startsWith("https://") ->
                    toast("Server URL must start with http:// or https://")
                token.isBlank() ->
                    toast("Enter the bearer token from your server's config.")
                userId <= 0 ->
                    toast("Enter your account ID (a whole number).")
                else -> {
                    SecureConfig.save(this, url, token, userId)
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
