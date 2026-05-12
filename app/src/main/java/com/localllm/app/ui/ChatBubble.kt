package com.localllm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import java.util.Date

/**
 * A single chat message. User messages are plain text and right-aligned with
 * the primary container background; assistant messages render markdown on the
 * left with `surfaceVariant`. Both support long-press copy and show a small
 * role/time header.
 *
 * The streaming reveal animation lives here: when [isStreaming] is true and
 * [lastDeltaLength] > 0, the trailing chunk of the assistant message fades in
 * from 50% to 100% opacity over 200ms via [animateColorAsState].
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
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }
    val roleLabel = if (isUser) stringResource(R.string.chat_role_user) else stringResource(R.string.chat_role_assistant)
    val time = remember(msg.timestampMs) {
        DateFormat.getTimeFormat(context).format(Date(msg.timestampMs))
    }
    val copyDescription = stringResource(R.string.chat_copy_message, msg.role)
    val copiedToast = stringResource(R.string.chat_copied)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        // Role + time header row
        Row(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isUser) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(bg, shape = shape)
                .padding(12.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("chat", msg.content))
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    }
                )
                .semantics { contentDescription = copyDescription }
        ) {
            if (isUser) {
                Text(
                    text = msg.content,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (isStreaming && lastDeltaLength in 1..msg.content.length) {
                StreamingAssistantText(
                    fullText = msg.content,
                    deltaLength = lastDeltaLength
                )
            } else {
                MarkdownText(text = msg.content)
            }
        }
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

    // Re-key on (fullText.length) so each appended chunk restarts the fade.
    // Using the buffer length (rather than `deltaLength`) avoids the case
    // where the same delta size repeats and Compose dedupes the key.
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
