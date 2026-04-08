package com.example.parlor.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.parlor.AppState
import com.example.parlor.UiState

/**
 * Main Compose screen for Parlor.
 *
 * Layout (portrait):
 *   ┌─────────────────────────────┐
 *   │   Camera preview (optional) │  40% height
 *   ├─────────────────────────────┤
 *   │   Status indicator + text   │
 *   │   Transcription bubble      │
 *   │   Response bubble           │
 *   │   Latency badges            │
 *   ├─────────────────────────────┤
 *   │   [Camera] button           │  bottom bar
 *   └─────────────────────────────┘
 */
@Composable
fun ParlourScreen(
    uiState: UiState,
    onCameraViewReady: (PreviewView) -> Unit,
    onToggleCamera: () -> Unit,
    onInterrupt: () -> Unit,
    onDebugTrigger: (() -> Unit)? = null,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // ── Camera preview ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.cameraEnabled,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { onCameraViewReady(it) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
            )
        }

        // ── Main content area ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Download progress bar
            if (uiState.appState == AppState.SETUP && uiState.downloadProgress > 0f) {
                DownloadProgress(uiState.downloadProgress)
                Spacer(Modifier.height(16.dp))
            }

            // Status indicator
            StatusIndicator(uiState.appState)
            Spacer(Modifier.height(12.dp))

            // Status text
            Text(
                text = uiState.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            // Error message
            if (uiState.appState == AppState.ERROR && uiState.errorMessage.isNotEmpty()) {
                ErrorCard(uiState.errorMessage)
                Spacer(Modifier.height(16.dp))
            }

            // Transcription bubble (what the user said)
            if (uiState.transcription.isNotEmpty()) {
                SpeechBubble(
                    label = "你说",
                    text = uiState.transcription,
                    isUser = true,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Response bubble (what the AI said)
            if (uiState.responseText.isNotEmpty()) {
                SpeechBubble(
                    label = "助手",
                    text = uiState.responseText,
                    isUser = false,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Latency badges
            if (uiState.llmLatencyMs > 0) {
                LatencyRow(uiState.llmLatencyMs, uiState.ttsLatencyMs)
            }
        }

        // ── Bottom bar ────────────────────────────────────────────────────────
        BottomBar(
            cameraEnabled = uiState.cameraEnabled,
            isSpeaking = uiState.appState == AppState.SPEAKING,
            isStubMode = uiState.isStubMode,
            isListening = uiState.appState == AppState.LISTENING,
            onToggleCamera = onToggleCamera,
            onInterrupt = onInterrupt,
            onDebugTrigger = onDebugTrigger,
        )
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun StatusIndicator(state: AppState) {
    val (color, label) = when (state) {
        AppState.SETUP      -> Color(0xFFFFA000) to "初始化"
        AppState.LOADING    -> Color(0xFFFFA000) to "加载中"
        AppState.LISTENING  -> Color(0xFF4CAF50) to "监听中"
        AppState.PROCESSING -> Color(0xFF2196F3) to "思考中"
        AppState.SPEAKING   -> Color(0xFF9C27B0) to "说话中"
        AppState.ERROR      -> Color(0xFFF44336) to "错误"
    }

    val alpha by animateFloatAsState(
        targetValue = if (state == AppState.PROCESSING || state == AppState.SPEAKING) 0.4f else 1f,
        label = "pulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun DownloadProgress(progress: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "下载模型中… ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeechBubble(label: String, text: String, isUser: Boolean) {
    val bgColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun LatencyRow(llmMs: Long, ttsMs: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (llmMs > 0) LatencyBadge("LLM", llmMs)
        if (ttsMs > 0) LatencyBadge("TTS", ttsMs)
    }
}

@Composable
private fun LatencyBadge(label: String, ms: Long) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = "$label ${ms}ms",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "错误: $message",
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BottomBar(
    cameraEnabled: Boolean,
    isSpeaking: Boolean,
    isStubMode: Boolean,
    isListening: Boolean,
    onToggleCamera: () -> Unit,
    onInterrupt: () -> Unit,
    onDebugTrigger: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Camera toggle
        OutlinedButton(onClick = onToggleCamera) {
            Text(if (cameraEnabled) "关闭摄像头" else "开启摄像头")
        }

        // Debug trigger — only shown on emulator (stub mode), while in LISTENING state
        if (isStubMode && isListening && onDebugTrigger != null) {
            Button(
                onClick = onDebugTrigger,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Text("模拟说话")
            }
        }

        // Interrupt / barge-in button (only visible when speaking)
        AnimatedVisibility(visible = isSpeaking) {
            Button(
                onClick = onInterrupt,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("打断")
            }
        }
    }
}
