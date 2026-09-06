package com.selfhost.remotesupport

import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), CaptureForegroundService.Companion.SessionListener {

    private lateinit var serverUrlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var codeText: TextView
    private lateinit var startButton: Button
    private lateinit var endButton: Button

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchSession(result.data!!)
        } else {
            statusText.text = "Screen-capture permission was not granted, so a session can't start."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        statusText = findViewById(R.id.statusText)
        codeText = findViewById(R.id.codeText)
        startButton = findViewById(R.id.startSessionButton)
        endButton = findViewById(R.id.endSessionButton)

        findViewById<Button>(R.id.grantAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startButton.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                statusText.text = "Please turn on the Accessibility permission first (step 1)."
                return@setOnClickListener
            }
            if (TextUtils.isEmpty(serverUrlInput.text)) {
                statusText.text = "Enter the signaling server address first."
                return@setOnClickListener
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        endButton.setOnClickListener {
            stopService(Intent(this, CaptureForegroundService::class.java))
            resetUi()
        }

        CaptureForegroundService.listener = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (CaptureForegroundService.listener === this) {
            CaptureForegroundService.listener = null
        }
    }

    private fun launchSession(projectionData: Intent) {
        statusText.text = "Requesting a pairing code..."
        val intent = Intent(this, CaptureForegroundService::class.java).apply {
            putExtra(CaptureForegroundService.EXTRA_SERVER_URL, serverUrlInput.text.toString().trim())
            putExtra(CaptureForegroundService.EXTRA_PROJECTION_DATA, projectionData)
            putExtra(CaptureForegroundService.EXTRA_TURN_URL, "")
            putExtra(CaptureForegroundService.EXTRA_TURN_USERNAME, "")
            putExtra(CaptureForegroundService.EXTRA_TURN_CREDENTIAL, "")
        }
        startForegroundService(intent)
        startButton.isEnabled = false
        endButton.visibility = android.view.View.VISIBLE
    }

    private fun resetUi() {
        codeText.text = ""
        statusText.text = "Session ended."
        startButton.isEnabled = true
        endButton.visibility = android.view.View.GONE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, RemoteControlAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.split(":").any {
            it.equals(expectedComponent.flattenToString(), ignoreCase = true)
        }
    }

    override fun onCode(code: String) {
        runOnUiThread { codeText.text = code }
    }

    override fun onStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    override fun onEnded() {
        runOnUiThread { resetUi() }
    }
}
