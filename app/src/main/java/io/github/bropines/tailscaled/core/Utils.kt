package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result
}

fun logSentFile(context: Context, fileName: String, targetName: String) {
    try {
        val historyFile = File(context.filesDir, "sent_history.json")
        val gson = Gson()
        val type = object : com.google.gson.reflect.TypeToken<MutableList<SentFileEntry>>() {}.type
        
        val history: MutableList<SentFileEntry> = if (historyFile.exists()) {
            gson.fromJson(historyFile.readText(), type)
        } else {
            mutableListOf()
        }
        
        history.add(0, SentFileEntry(fileName, targetName, System.currentTimeMillis()))
        if (history.size > 50) history.removeAt(history.size - 1)
        
        historyFile.writeText(gson.toJson(history))
    } catch (e: Exception) {}
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

object BackupCrypto {
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        java.security.SecureRandom().nextBytes(salt)

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH)
        java.security.SecureRandom().nextBytes(iv)
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val ciphertext = cipher.doFinal(data)

        val result = ByteArray(SALT_LENGTH + IV_LENGTH + ciphertext.size)
        System.arraycopy(salt, 0, result, 0, SALT_LENGTH)
        System.arraycopy(iv, 0, result, SALT_LENGTH, IV_LENGTH)
        System.arraycopy(ciphertext, 0, result, SALT_LENGTH + IV_LENGTH, ciphertext.size)
        return result
    }

    fun decrypt(encryptedData: ByteArray, password: CharArray): ByteArray {
        if (encryptedData.size < SALT_LENGTH + IV_LENGTH) {
            throw IllegalArgumentException("Data too short")
        }

        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        System.arraycopy(encryptedData, 0, salt, 0, SALT_LENGTH)
        System.arraycopy(encryptedData, SALT_LENGTH, iv, 0, IV_LENGTH)

        val ciphertextLength = encryptedData.size - SALT_LENGTH - IV_LENGTH
        val ciphertext = ByteArray(ciphertextLength)
        System.arraycopy(encryptedData, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertextLength)

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(ciphertext)
    }
}

fun wrapContextWithLocale(context: Context): Context {
    val lang = GlobalSettings.getString(context, "app_locale", "sys")
    return if (lang == "sys") {
        context
    } else {
        val locale = java.util.Locale.forLanguageTag(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.createConfigurationContext(config)
    }
}

@Composable
fun PredictiveBackContainer(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (onBack == null) {
        content()
        return
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                backProgress = backEvent.progress
            }
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    val backScale = 1f - (backProgress * 0.12f)
    val backAlpha = 1f - (backProgress * 0.3f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = backScale
                scaleY = backScale
                alpha = backAlpha
                clip = true
                shape = RoundedCornerShape((backProgress * 28).dp)
            }
    ) {
        content()
    }
}

@Composable
fun SlidingSegmentedChips(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    positionOffset: Float? = null,
    height: Dp = 40.dp
) {
    val animPosition by animateFloatAsState(
        targetValue = positionOffset ?: selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 220),
        label = "slidingPosition"
    )

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val totalWidth = maxWidth
        val count = options.size.coerceAtLeast(1)
        val itemWidth = totalWidth / count

        // Smooth Continuous Drag-Bound / Animated Active Pill
        val offsetX = itemWidth * animPosition.coerceIn(0f, (count - 1).toFloat())
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
        )

        // Labels overlay
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                val isSelected = (animPosition - index).let { Math.abs(it) < 0.5f }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onOptionSelected(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


