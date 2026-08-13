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
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear

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
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (label != null) { { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } } else null,
        placeholder = if (placeholder != null) { { Text(placeholder, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } } else null,
        supportingText = if (supportingText != null) { { Text(supportingText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } } else null,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = true,
        maxLines = 1,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        ),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = {
                onSearch?.invoke()
                focusManager.clearFocus()
            },
            onDone = {
                focusManager.clearFocus()
            }
        ),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                placeholder = {
                    Text(
                        text = placeholderText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = if (value.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onValueChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                },
                contentPadding = PaddingValues(start = 12.dp, top = 0.dp, end = 8.dp, bottom = 0.dp)
            )
        }
    )
}

@Composable
fun PredictiveBackContainer(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    targetTitle: String? = null,
    targetIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
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
    val backAlpha = 1f - (backProgress * 0.35f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Destination preview card in the background (visible as main screen scales down)
        if (backProgress > 0.001f) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .graphicsLayer {
                            val scale = 0.85f + (backProgress * 0.15f)
                            scaleX = scale
                            scaleY = scale
                            alpha = (backProgress * 3f).coerceIn(0f, 1f)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = targetIcon ?: Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = targetTitle ?: androidx.compose.ui.res.stringResource(R.string.action_back),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Main screen content scaling down
        Box(
            modifier = Modifier
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
}

data class SegmentedChipItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val containerColor: androidx.compose.ui.graphics.Color? = null,
    val contentColor: androidx.compose.ui.graphics.Color? = null
)

@JvmName("SlidingSegmentedChipsOptions")
@Composable
fun SlidingSegmentedChips(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    positionOffset: Float? = null,
    height: Dp = 40.dp
) {
    SlidingSegmentedChips(
        items = options.map { SegmentedChipItem(it) },
        selectedIndex = selectedIndex,
        onOptionSelected = onOptionSelected,
        modifier = modifier,
        positionOffset = positionOffset,
        height = height
    )
}

@Composable
fun SlidingSegmentedChips(
    items: List<SegmentedChipItem>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    positionOffset: Float? = null,
    height: Dp = 40.dp
) {
    val animPosition by animateFloatAsState(
        targetValue = positionOffset ?: selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 60),
        label = "slidingPosition"
    )

    val count = items.size.coerceAtLeast(1)
    val activeIndex = animPosition.roundToInt().coerceIn(0, count - 1)
    val selectedItem = items.getOrNull(activeIndex)
    val defaultContainer = MaterialTheme.colorScheme.primaryContainer
    val targetContainer = selectedItem?.containerColor ?: defaultContainer

    val activeBgColor by androidx.compose.animation.animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 60),
        label = "activePillBg"
    )

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val totalWidth = maxWidth
        val itemWidth = totalWidth / count

        // Smooth Continuous Drag-Bound / Animated Active Pill
        val offsetX = itemWidth * animPosition.coerceIn(0f, (count - 1).toFloat())
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(activeBgColor)
        )

        // Labels & Icons overlay
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val isSelected = (animPosition - index).let { Math.abs(it) < 0.5f }
                val defaultContent = MaterialTheme.colorScheme.onPrimaryContainer
                val targetContent = if (isSelected) (item.contentColor ?: defaultContent) else MaterialTheme.colorScheme.onSurfaceVariant
                val contentColor by androidx.compose.animation.animateColorAsState(
                    targetValue = targetContent,
                    animationSpec = tween(durationMillis = 60),
                    label = "chipContentColor"
                )

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.icon != null) {
                            androidx.compose.material3.Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = contentColor
                            )
                        }
                        Text(
                            text = item.title,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}

@JvmName("ScrollableSlidingSegmentedChipsOptions")
@Composable
fun ScrollableSlidingSegmentedChips(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector?>? = null,
    height: Dp = 40.dp
) {
    val items = options.mapIndexed { index, title ->
        SegmentedChipItem(title, icons?.getOrNull(index))
    }
    ScrollableSlidingSegmentedChips(
        items = items,
        selectedIndex = selectedIndex,
        onOptionSelected = onOptionSelected,
        modifier = modifier,
        height = height
    )
}

@Composable
fun ScrollableSlidingSegmentedChips(
    items: List<SegmentedChipItem>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }

    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items.size) { index ->
            val item = items[index]
            val isSelected = selectedIndex == index
            val defaultContainer = MaterialTheme.colorScheme.primaryContainer
            val defaultContent = MaterialTheme.colorScheme.onPrimaryContainer
            val itemContainer = item.containerColor ?: defaultContainer
            val itemContent = item.contentColor ?: defaultContent

            val bgColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) itemContainer else androidx.compose.ui.graphics.Color.Transparent,
                animationSpec = tween(durationMillis = 60),
                label = "chipBgColor"
            )
            val contentColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) itemContent else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = 60),
                label = "chipContentColor"
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onOptionSelected(index)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.icon != null) {
                        androidx.compose.material3.Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = contentColor
                        )
                    }
                    Text(
                        text = item.title,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = contentColor
                    )
                }
            }
        }
    }
}


