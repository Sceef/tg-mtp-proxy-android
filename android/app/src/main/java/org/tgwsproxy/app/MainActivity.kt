package org.tgwsproxy.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tgwsproxy.app.databinding.ActivityMainBinding
import org.tgwsproxy.core.buildTgProxyLink
import org.tgwsproxy.core.getLinkHost

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { ProxyPreferencesRepository(this) }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ProxyForegroundService.start(this)
            binding.textStatus.setText(R.string.status_running)
        } else {
            Toast.makeText(this, R.string.toast_notif_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonSave.setOnClickListener {
            lifecycleScope.launch {
                if (persistForm()) {
                    Toast.makeText(this@MainActivity, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.buttonCopyLink.setOnClickListener { copyLink() }
        binding.buttonStart.setOnClickListener {
            lifecycleScope.launch {
                if (!persistForm()) return@launch
                withContext(Dispatchers.Main) {
                    startProxyWithPermission()
                }
            }
        }
        binding.buttonStop.setOnClickListener {
            ProxyForegroundService.stop(this)
            binding.textStatus.setText(R.string.status_stopped)
        }

        lifecycleScope.launch {
            prefs.ensureSecretIfEmpty()
            val cfg = prefs.currentConfig()
            binding.editHost.setText(cfg.host)
            binding.editPort.setText(cfg.port.toString())
            binding.editSecret.setText(cfg.secretHex)
            binding.editDcLines.setText(prefs.rawDcLines())
            refreshLinkPreview()
        }
    }

    /** @return true if values were written to DataStore */
    private suspend fun persistForm(): Boolean {
        val host = binding.editHost.text?.toString()?.trim() ?: ""
        val portStr = binding.editPort.text?.toString()?.trim() ?: ""
        val secret = binding.editSecret.text?.toString()?.trim()?.lowercase() ?: ""
        val dcLines = binding.editDcLines.text?.toString() ?: ""

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, R.string.toast_invalid_port, Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (secret.length != 32 || !secret.all { it in '0'..'9' || it in 'a'..'f' }) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, R.string.toast_invalid_secret, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        prefs.save(host.ifEmpty { "127.0.0.1" }, port, secret, dcLines)
        withContext(Dispatchers.Main) { refreshLinkPreview() }
        return true
    }

    private fun refreshLinkPreview() {
        val host = binding.editHost.text?.toString()?.trim()?.ifEmpty { "127.0.0.1" } ?: "127.0.0.1"
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull() ?: 1443
        val secret = binding.editSecret.text?.toString()?.trim()?.lowercase() ?: ""
        if (secret.length == 32) {
            binding.textLink.text = buildTgProxyLink(getLinkHost(host), port, secret)
        } else {
            binding.textLink.text = ""
        }
    }

    private fun copyLink() {
        refreshLinkPreview()
        val text = binding.textLink.text?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.toast_invalid_secret, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tg-proxy", text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProxyWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(perm)
                return
            }
        }
        ProxyForegroundService.start(this)
        binding.textStatus.setText(R.string.status_running)
    }
}
