package chat.homebuddy.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The little Markdown answers use: paragraphs, headings, bullet and numbered lists, bold, italics, code. */
sealed interface MarkdownBlock {
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock
    data class Heading(val text: AnnotatedString) : MarkdownBlock
    data class Bullet(val text: AnnotatedString) : MarkdownBlock
    data class Numbered(val number: String, val text: AnnotatedString) : MarkdownBlock
}

private val NUMBERED = Regex("""^(\d+)[.)]\s+(.*)$""")
private val BULLET = Regex("""^[-*•]\s+(.*)$""")
private val HEADING = Regex("""^#{1,6}\s+(.*)$""")

fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(inline(paragraph.joinToString(" ")))
        paragraph.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        val numbered = NUMBERED.find(line)
        val bullet = BULLET.find(line)
        val heading = HEADING.find(line)
        when {
            line.isEmpty() -> flush()
            heading != null -> { flush(); blocks += MarkdownBlock.Heading(inline(heading.groupValues[1])) }
            numbered != null -> { flush(); blocks += MarkdownBlock.Numbered(numbered.groupValues[1], inline(numbered.groupValues[2])) }
            bullet != null -> { flush(); blocks += MarkdownBlock.Bullet(inline(bullet.groupValues[1])) }
            else -> paragraph += line
        }
    }
    flush()
    return blocks
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`(.+?)`|\[(.+?)]\((.+?)\)""")

internal fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(last, match.range.first))
        val (bold, boldAlt, italic, italicAlt, code, linkText) = match.destructured
        when {
            bold.isNotEmpty() || boldAlt.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold + boldAlt) }
            italic.isNotEmpty() || italicAlt.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic + italicAlt) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> append(linkText)
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parseMarkdown(markdown).forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                is MarkdownBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleMedium)
                is MarkdownBlock.Bullet -> ListItemRow("•", block.text)
                is MarkdownBlock.Numbered -> ListItemRow("${block.number}.", block.text)
            }
        }
    }
}

@Composable
private fun ListItemRow(marker: String, text: AnnotatedString) {
    Row {
        Text(marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
