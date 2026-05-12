package com.localllm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Renders a markdown string as native Compose text. Walks the CommonMark AST
 * and emits a column of inline-aware text blocks (paragraphs, code blocks,
 * lists, headings). Inline styles (bold, italic, code, links) live inside an
 * [AnnotatedString]; block-level constructs render as separate composables so
 * spacing and backgrounds compose cleanly.
 *
 * No WebView. No HTML. The Gemma 4 chat output is the primary caller.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val parser = remember { Parser.builder().build() }
    val document = remember(text) { parser.parse(text) }
    Column(modifier = modifier) {
        RenderChildren(document, depth = 0)
    }
}

@Composable
private fun RenderChildren(parent: Node, depth: Int) {
    var child = parent.firstChild
    var first = true
    while (child != null) {
        if (!first) Spacer(modifier = Modifier.height(6.dp))
        RenderBlock(child, depth)
        first = false
        child = child.next
    }
}

@Composable
private fun RenderBlock(node: Node, depth: Int) {
    when (node) {
        is Heading -> RenderHeading(node)
        is Paragraph -> Text(
            text = buildInline(node),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        is FencedCodeBlock -> CodeBlock(node.literal.orEmpty())
        is IndentedCodeBlock -> CodeBlock(node.literal.orEmpty())
        is BulletList -> RenderList(node, ordered = false, depth = depth)
        is OrderedList -> RenderList(node, ordered = true, depth = depth)
        is BlockQuote -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(8.dp)
        ) {
            Column { RenderChildren(node, depth + 1) }
        }
        is ThematicBreak -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        else -> {
            val annotated = buildInline(node)
            if (annotated.text.isNotEmpty()) {
                Text(
                    text = annotated,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun RenderHeading(node: Heading) {
    val style = when (node.level) {
        1 -> MaterialTheme.typography.titleMedium
        2 -> MaterialTheme.typography.titleMedium
        3 -> MaterialTheme.typography.titleSmall
        4 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyLarge
    }
    Text(
        text = buildInline(node),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun CodeBlock(code: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        Text(
            text = code.trimEnd('\n'),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 40.dp)
        )
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(context, context.getString(R.string.chat_code_copied), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = context.getString(R.string.chat_code_copy),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RenderList(parent: Node, ordered: Boolean, depth: Int) {
    if (depth >= 2) {
        Text(
            text = buildInline(parent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        return
    }
    Column(modifier = Modifier.padding(start = (depth * 12).dp)) {
        var item = parent.firstChild
        var index = 1
        while (item != null) {
            if (item is ListItem) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    val prefix = if (ordered) "$index. " else "• "
                    Text(
                        text = prefix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        RenderChildren(item, depth + 1)
                    }
                }
                index++
            }
            item = item.next
        }
    }
}

/** Build an [AnnotatedString] for any node whose children we want as a single styled run. */
@Composable
private fun buildInline(parent: Node): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        appendInline(this, parent, codeBg, linkColor)
    }
}

private fun appendInline(
    builder: AnnotatedString.Builder,
    parent: Node,
    codeBg: Color,
    linkColor: Color,
) {
    var node: Node? = parent.firstChild
    while (node != null) {
        when (val n = node) {
            is MdText -> builder.append(n.literal)
            is Code -> {
                builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(n.literal)
                }
            }
            is StrongEmphasis -> {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(builder, n, codeBg, linkColor)
                }
            }
            is Emphasis -> {
                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInline(builder, n, codeBg, linkColor)
                }
            }
            is Link -> {
                builder.pushStringAnnotation(tag = "URL", annotation = n.destination.orEmpty())
                builder.withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendInline(builder, n, codeBg, linkColor)
                }
                builder.pop()
            }
            is SoftLineBreak -> builder.append(' ')
            is HardLineBreak -> builder.append('\n')
            else -> appendInline(builder, n, codeBg, linkColor)
        }
        node = node.next
    }
}

// Strikethrough is a GFM extension and isn't enabled by the core Parser.
// If we wanted GFM we'd add `commonmark-ext-gfm-strikethrough`. Keeping it minimal.

@Preview(showBackground = true)
@Composable
private fun MarkdownTextPreview() {
    val sample = """
        # Gemma 4 quickstart

        Run the **local** server on a *Pixel 6* and hit `/v1/chat/completions`.

        ```kotlin
        val client = OkHttpClient()
        ```

        - First bullet
        - Second bullet with `inline code`
        - [Link to docs](https://example.com)

        1. Numbered one
        2. Numbered two
    """.trimIndent()
    MaterialTheme {
        Box(modifier = Modifier.padding(12.dp)) {
            MarkdownText(text = sample)
        }
    }
}
