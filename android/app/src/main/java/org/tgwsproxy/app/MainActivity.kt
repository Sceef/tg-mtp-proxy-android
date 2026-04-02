package org.tgwsproxy.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
        setSupportActionBar(binding.toolbar)

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
            refreshLinkPreview()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** @return true if values were written to DataStore */
    private suspend fun persistForm(): Boolean {
        val host = binding.editHost.text?.toString()?.trim() ?: ""
        val portStr = binding.editPort.text?.toString()?.trim() ?: ""
        val secret = binding.editSecret.text?.toString()?.trim()?.lowercase() ?: ""

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

        prefs.saveMain(host.ifEmpty { "127.0.0.1" }, port, secret)
        withContext(Dispatchers.Main) { refreshLinkPreview() }
        return true
    }

    private fun refreshLinkPreview() {
        val host = binding.editHost.text?.toString()?.trim()?.ifEmpty { "127.0.0.1" } ?: "127.0.0.1"
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull() ?: 1443
        val secret = binding.editSecret.text?.toString()?.trim()?.lowercase() ?: ""
        if (secret.length == 32) {
            val url = buildTgProxyLink(getLinkHost(host), port, secret)
            applyClickableProxyLink(url)
        } else {
            binding.textLink.text = ""
            binding.textLink.movementMethod = null
        }
    }

    private fun linkTextColor(): Int {
        val attrs = intArrayOf(android.R.attr.textColorLink)
        val ta = theme.obtainStyledAttributes(attrs)
        val c = ta.getColor(0, 0xFF1976D2.toInt())
        ta.recycle()
        return c
    }

    /** Показывает URL как одну кликабельную ссылку (открывает tg:// в Telegram). */
    private fun applyClickableProxyLink(url: String) {
        val spanStr = SpannableString(url)
        val clickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                openTgProxyLink(url)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color = linkTextColor()
            }
        }
        spanStr.setSpan(clickable, 0, url.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.textLink.text = spanStr
        binding.textLink.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openTgProxyLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_app_for_tg, Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        openTgProxyLink(text)
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
