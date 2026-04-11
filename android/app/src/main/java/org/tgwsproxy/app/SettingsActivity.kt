package org.tgwsproxy.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.tgwsproxy.app.databinding.ActivitySettingsBinding
import org.tgwsproxy.core.ProxyConfig
import java.util.regex.Pattern

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { ProxyPreferencesRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            val cfg = prefs.currentConfig()
            binding.editDcLines.setText(prefs.rawDcLines())
            binding.editBufferSize.setText(cfg.bufferSize.toString())
            binding.editPoolSize.setText(cfg.poolSize.toString())
            binding.switchCfproxyEnabled.isChecked = cfg.fallbackCfproxy
            binding.switchCfproxyPriority.isChecked = cfg.cfproxyPriority
            binding.switchCfproxyFetchRemote.isChecked = cfg.cfproxyFetchRemote
            binding.editCfproxyUserDomain.setText(cfg.cfproxyUserDomain)
        }

        binding.buttonSaveSettings.setOnClickListener {
            lifecycleScope.launch {
                if (validateAndSave()) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.toast_settings_saved,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            }
        }
    }

    private suspend fun validateAndSave(): Boolean {
        val dcText = binding.editDcLines.text?.toString() ?: ""
        val trimmedLines = dcText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedLines.isNotEmpty()) {
            try {
                ProxyConfig.parseDcIpList(trimmedLines)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_invalid_dc_lines, Toast.LENGTH_LONG).show()
                return false
            }
        }

        val bufferStr = binding.editBufferSize.text?.toString()?.trim().orEmpty()
        val bufferSize = bufferStr.toIntOrNull()
        if (bufferSize == null ||
            bufferSize < ProxyConfig.BUFFER_SIZE_MIN ||
            bufferSize > ProxyConfig.BUFFER_SIZE_MAX
        ) {
            Toast.makeText(this, R.string.toast_invalid_buffer_size, Toast.LENGTH_LONG).show()
            return false
        }

        val poolStr = binding.editPoolSize.text?.toString()?.trim().orEmpty()
        val poolSize = poolStr.toIntOrNull()
        if (poolSize == null ||
            poolSize < ProxyConfig.POOL_SIZE_MIN ||
            poolSize > ProxyConfig.POOL_SIZE_MAX
        ) {
            Toast.makeText(this, R.string.toast_invalid_pool_size, Toast.LENGTH_LONG).show()
            return false
        }

        val cfUser = binding.editCfproxyUserDomain.text?.toString()?.trim().orEmpty()
        if (cfUser.isNotEmpty() && !CFPROXY_DOMAIN_PATTERN.matcher(cfUser).matches()) {
            Toast.makeText(this, R.string.toast_invalid_cfproxy_domain, Toast.LENGTH_LONG).show()
            return false
        }

        prefs.saveAdvanced(
            dcLines = dcText,
            bufferSize = bufferSize,
            poolSize = poolSize,
            fallbackCfproxy = binding.switchCfproxyEnabled.isChecked,
            cfproxyPriority = binding.switchCfproxyPriority.isChecked,
            cfproxyUserDomain = cfUser,
            cfproxyFetchRemote = binding.switchCfproxyFetchRemote.isChecked,
        )
        return true
    }

    companion object {
        private val CFPROXY_DOMAIN_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\$",
        )
    }
}
