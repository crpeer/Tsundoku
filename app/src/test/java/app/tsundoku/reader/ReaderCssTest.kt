package app.tsundoku.reader

import app.tsundoku.reader.data.prefs.ReaderFontFamily
import app.tsundoku.reader.data.prefs.ReaderFontWeight
import app.tsundoku.reader.data.prefs.ReaderLayout
import app.tsundoku.reader.data.prefs.ReaderSettings
import app.tsundoku.reader.data.prefs.ReaderTheme
import app.tsundoku.reader.data.prefs.ReaderWritingMode
import app.tsundoku.reader.ui.reader.cssFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器 CSS 结构。
 *
 * 翻页模式必须走「整页纵向滚动」，不能再让 CSS 多栏参与分页：
 * 列宽是以 CSS px 计算的，而 WebView 的宽度是物理像素，两者实测不成 1:1，
 * 一旦用列宽当翻页步长就会出现「页码涨了但正文不动 / 末尾空白页」。
 */
class ReaderCssTest {

    private fun css(
        layout: ReaderLayout,
        writingMode: ReaderWritingMode = ReaderWritingMode.HORIZONTAL,
    ): String = cssFor(
        theme = ReaderTheme.LIGHT,
        s = ReaderSettings(
            fontSize = 18,
            lineHeight = 1.6f,
            paragraphSpacing = 0.5f,
            textIndent = 2f,
            horizontalPadding = 20,
            fontFamily = ReaderFontFamily.SERIF,
            fontWeight = ReaderFontWeight.NORMAL,
            theme = ReaderTheme.LIGHT,
            writingMode = writingMode,
            layout = layout,
        ),
        viewWidthPx = 720,
        viewHeightPx = 1200,
    )

    @Test
    fun pagedCssDoesNotUseMultiColumnLayout() {
        val paged = css(ReaderLayout.PAGED)
        // column-width/column-gap 只允许以 auto/normal 的形式出现在「压制」规则里
        assertFalse(paged.contains("column-width: 3"))
        assertFalse(paged.contains("column-gap: 40"))
        assertTrue(paged.contains("-webkit-column-width: auto !important"))
        assertTrue(paged.contains("-webkit-column-gap: normal !important"))
    }

    @Test
    fun pagedCssKeepsVerticalScrollingEnabled() {
        val paged = css(ReaderLayout.PAGED)
        assertTrue(paged.contains("overflow-y: auto !important"))
        assertTrue(paged.contains("overflow-x: hidden !important"))
    }

    @Test
    fun pagedCssNeverFixesPageHeight() {
        val paged = css(ReaderLayout.PAGED)
        // 固定高度会把内容裁掉，翻页位置就与正文对不上了
        assertTrue(paged.contains("height: auto !important"))
        assertFalse(paged.contains("height: 1200px"))
    }

    @Test
    fun scrollCssHasNoPagedGuard() {
        val scroll = css(ReaderLayout.SCROLL)
        assertFalse(scroll.contains("overflow-y: auto !important"))
        assertFalse(scroll.contains("-webkit-column-width: auto !important"))
        // 滚动模式底部留白，避免最后一行贴着屏幕边缘
        assertTrue(scroll.contains("padding: 8px 20px 64px 20px"))
    }

    @Test
    fun verticalCssUsesMeasuredHeight() {
        val vertical = css(ReaderLayout.SCROLL, ReaderWritingMode.VERTICAL)
        assertTrue(vertical.contains("writing-mode: vertical-rl"))
        assertTrue(vertical.contains("height: 1200px"))
    }
}
