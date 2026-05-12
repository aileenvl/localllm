package com.localllm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import java.util.Date

/**
 * A single chat message.
 *
 *  - **Assistant**: borderless, full-bleed. A 3dp left-rail strip in
 *    primary@50% is the only visual marker. No icon, no role label, no
 *    bubble outline. Markdown body fills the row.
 *  - **User**: right-aligned pill on `primaryContainer`. Timestamp sits
 *    below the pill, right-aligned.
 *
 * Long-press copies the message body to the clipboard. The streaming
 * fade-in (assistant only) is preserved from v1.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    msg: UiMessage,
    isStreaming: Boolean = false,
    lastDeltaLength: Int = 0,
) {
    val context = LocalContext.current
    val isUser = msg.role == "user"
    val time = remember(msg.timestampMs) {
        DateFormat.getTimeFormat(context).format(Date(msg.timestampMs))
    }
    val copyDescription = stringResource(R.string.chat_copy_message, msg.role)
    val copiedToast = stringResource(R.string.chat_copied)

    val copyOnLongPress: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("chat", msg.content))
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
    }

    if (isUser) {
        UserBubble(
            text = msg.content,
            time = time,
            copyDescription = copyDescription,
            onLongPress = copyOnLongPress,
        )
    } else {
        AssistantBubble(
            content = msg.content,
            time = time,
            isStreaming = isStreaming,
            lastDeltaLength = lastDeltaLength,
            copyDescription = copyDescription,
            onLongPress = copyOnLongPress,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    content: String,
    time: String,
    isStreaming: Boolean,
    lastDeltaLength: Int,
    copyDescription: String,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 6.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .semantics { contentDescription = copyDescription }
    ) {
        // 3dp accent rail, full-height of the message body.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(end = 16.dp)) {
            if (isStreaming && lastDeltaLength in 1..content.length) {
                StreamingAssistantText(
                    fullText = content,
                    deltaLength = lastDeltaLength
                )
            } else {
                MarkdownText(text = content)
            }
            // Hide timestamp during streaming; only show on a finished message.
            if (!isStreaming) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    text: String,
    time: String,
    copyDescription: String,
    onLongPress: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val maxPillWidth = (configuration.screenWidthDp * 0.8f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 0.dp,
            modifier = Modifier
                .widthIn(max = maxPillWidth)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                )
                .semantics { contentDescription = copyDescription },
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * While streaming, fall back to plain text rendering (markdown re-parses are
 * cheap with commonmark but doing them per chunk *while* animating a span is
 * jittery). We split the buffer into the stable head and the freshly-appended
 * tail; the tail fades in from 50% to 100% opacity over 200ms.
 *
 * Each new delta resets the animation by `key(...)` re-keying the Animatable.
 *
 * Once streaming finishes, the parent bubble swaps in [MarkdownText] for a
 * proper formatted render.
 */
@Composable
private fun StreamingAssistantText(fullText: String, deltaLength: Int) {
    val clamped = deltaLength.coerceIn(0, fullText.length)
    val head = fullText.substring(0, fullText.length - clamped)
    val tail = fullText.substring(fullText.length - clamped)

    val baseColor = MaterialTheme.colorScheme.onSurface

    key(fullText.length) {
        val alpha = remember { Animatable(0.5f) }
        LaunchedEffect(Unit) {
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 200))
        }
        val annotated: AnnotatedString = buildAnnotatedString {
            append(head)
            withStyle(SpanStyle(color = baseColor.copy(alpha = alpha.value))) {
                append(tail)
            }
        }
        Text(
            text = annotated,
            color = baseColor,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
