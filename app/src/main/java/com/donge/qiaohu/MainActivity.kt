package com.donge.qiaohu

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.donge.qiaohu.camera.CameraCapture
import com.donge.qiaohu.ui.QiaohuScreen
import com.donge.qiaohu.ui.theme.QiaohuTheme
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: QiaohuViewModel by viewModels()

    // ── Permission launcher ───────────────────────────────────────────────────

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted    = results[Manifest.permission.RECORD_AUDIO] == true
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        Log.i(TAG, "Permissions: mic=$micGranted camera=$cameraGranted")
        if (!micGranted) {
            Log.e(TAG, "Microphone permission denied — app cannot function")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request runtime permissions immediately
        permissionsLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        )

        setContent {
            QiaohuTheme {
                val uiState by viewModel.uiState.collectAsState()
                QiaohuScreen(
                    uiState = uiState,
                    onCameraViewReady = { previewView ->
                        // Wire up CameraCapture once we have a PreviewView
                        val capture = CameraCapture(
                            context = this@MainActivity,
                            lifecycleOwner = this@MainActivity,
                            previewView = previewView,
                        )
                        if (uiState.cameraEnabled) capture.start()
                        viewModel.cameraCapture = capture
                    },
                    onToggleCamera = { viewModel.toggleCamera() },
                    onInterrupt = { viewModel.interrupt() },
                    onDebugTrigger = { viewModel.debugTriggerSpeech() },
                )
            }
        }
    }
}
