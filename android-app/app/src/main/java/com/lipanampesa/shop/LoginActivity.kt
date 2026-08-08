package com.lipanampesa.shop

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class LoginActivity : AppCompatActivity() {

    private lateinit var session: Session
    private var discovery: ServerDiscovery? = null
    private lateinit var etServer: TextInputEditText
    private lateinit var tvDiscovery: TextView

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { applySetupCode(it) }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanQr()
            else Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)

        // Deep link: "Open app & connect" on the server's /install page (or setup QR).
        val deepLinked = applyDeepLink(intent)

        if (session.isLoggedIn) {
            routeByRole()
            return
        }
        setContentView(R.layout.activity_login)

        etServer = findViewById(R.id.etServerUrl)
        val etUser = findViewById<TextInputEditText>(R.id.etUsername)
        val etPass = findViewById<TextInputEditText>(R.id.etPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvError = findViewById<TextView>(R.id.tvError)
        tvDiscovery = findViewById(R.id.tvDiscovery)

        etServer.setText(session.baseUrl)
        findViewById<MaterialButton>(R.id.btnDetect).setOnClickListener { startDiscovery() }
        findViewById<MaterialButton>(R.id.btnScanQr).setOnClickListener {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<MaterialButton>(R.id.btnKiosk).setOnClickListener {
            startActivity(Intent(this, KioskActivity::class.java))
        }

        btnLogin.setOnClickListener {
            val username = etUser.text.toString().trim()
            val password = etPass.text.toString()
            tvError.text = ""
            if (username.isEmpty() || password.isEmpty()) {
                tvError.text = getString(R.string.invalid_login)
                return@setOnClickListener
            }
            session.baseUrl = etServer.text.toString().trim()
            btnLogin.isEnabled = false
            progress.visibility = View.VISIBLE
            tvError.text = getString(R.string.logging_in)

            ApiClient.service(this).login(LoginRequest(username, password))
                .go({ resp ->
                    session.token = resp.token
                    session.role = resp.user.role
                    session.displayName = resp.user.displayName
                    session.shopName = resp.shop?.name
                    routeByRole()
                }, { err ->
                    btnLogin.isEnabled = true
                    progress.visibility = View.GONE
                    tvError.text = err
                })
        }

        if (!deepLinked) startDiscovery()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDeepLink(intent)
    }

    /** Reads mpesashop://connect?url=... intents; returns true when one was applied. */
    private fun applyDeepLink(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "mpesashop") return false
        val url = data.getQueryParameter("url") ?: return false
        session.baseUrl = url
        if (::etServer.isInitialized) etServer.setText(url)
        if (::tvDiscovery.isInitialized) {
            tvDiscovery.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            tvDiscovery.text = getString(R.string.server_found, url)
        }
        return true
    }

    /* ---------------- server auto-detection (mDNS) + setup QR ---------------- */

    private fun startDiscovery() {
        discovery?.stop()
        tvDiscovery.setTextColor(ContextCompat.getColor(this, R.color.gray))
        tvDiscovery.text = getString(R.string.searching_server)
        discovery = ServerDiscovery(
            this,
            onFound = { url ->
                session.baseUrl = url
                etServer.setText(url)
                tvDiscovery.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                tvDiscovery.text = getString(R.string.server_found, url)
            },
            onNotFound = {
                tvDiscovery.setTextColor(ContextCompat.getColor(this, R.color.gray))
                tvDiscovery.text = getString(R.string.server_not_found)
            }
        ).also { it.start() }
    }

    private fun scanQr() {
        qrLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt_qr))
                .setBeepEnabled(true)
        )
    }

    /** Accepts "mpesashop://connect?url=..." (from the server console/install page) or a raw http(s) URL. */
    private fun applySetupCode(contents: String) {
        val text = contents.trim()
        val url = when {
            text.startsWith("mpesashop://") -> Uri.parse(text).getQueryParameter("url")
            text.startsWith("http://") || text.startsWith("https://") -> text
            else -> null
        }
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.invalid_qr, Toast.LENGTH_LONG).show()
            return
        }
        session.baseUrl = url
        etServer.setText(url)
        tvDiscovery.setTextColor(ContextCompat.getColor(this, R.color.status_success))
        tvDiscovery.text = getString(R.string.server_found, url)
    }

    override fun onDestroy() {
        discovery?.stop()
        super.onDestroy()
    }

    private fun routeByRole() {
        val target = if (session.role == "admin") AdminActivity::class.java else CashierActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
