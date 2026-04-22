package com.bohanli.ruzhtranslator.settings

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bohanli.ruzhtranslator.R
import com.bohanli.ruzhtranslator.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // --- ASR model selection ---
        binding.radioAsrModel.check(
            if (AppSettings.asrModel == AppSettings.ASR_MODEL_LARGE)
                binding.rbAsrLarge.id
            else binding.rbAsrSmall.id
        )
        binding.radioAsrModel.setOnCheckedChangeListener { _, checkedId ->
            val newModel = when (checkedId) {
                binding.rbAsrLarge.id -> AppSettings.ASR_MODEL_LARGE
                else -> AppSettings.ASR_MODEL_SMALL
            }
            if (newModel != AppSettings.asrModel) {
                AppSettings.asrModel = newModel
                Toast.makeText(this, getString(R.string.asr_changed_toast), Toast.LENGTH_SHORT).show()
            }
        }

        // --- Drift detection toggle ---
        binding.switchDriftRetry.isChecked = AppSettings.driftRetryEnabled
        binding.switchDriftRetry.setOnCheckedChangeListener { _, checked ->
            AppSettings.driftRetryEnabled = checked
            Log.i(TAG, "Drift retry toggled: $checked")
        }
    }
}
