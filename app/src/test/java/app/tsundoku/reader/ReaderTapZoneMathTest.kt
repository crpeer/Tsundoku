package app.tsundoku.reader

import app.tsundoku.reader.data.prefs.ReaderTapZone
import app.tsundoku.reader.ui.reader.ReaderTapZoneMath
import app.tsundoku.reader.ui.reader.TapAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 点击区域判定。
 *
 * 回归的是这个缺陷：`disabled` 返回 0，而调用方把 0 当成「中间区域 → 显隐工具栏」，
 * 于是「禁用点击区域」之后点正文仍会切换工具栏。
 * 现在 `disabled` 有独立的返回值 [TapAction.NONE]，和中间区域 [TapAction.MENU] 不再混淆。
 */
class ReaderTapZoneMathTest {

    private val w = 411f
    private val h = 624f

    private fun at(zone: ReaderTapZone, x: Float, y: Float = h / 2f): TapAction =
        ReaderTapZoneMath.resolve(zone, x, y, w, h)

    // ---------- 问题一：禁用状态 ----------

    @Test
    fun disabledZoneDoesNothingAnywhere() {
        // 左、中、右、底部（L 形会命中的区域）全部都不响应
        val xs = listOf(-100f, 0f, 1f, w / 2f, w - 1f, w, w + 100f)
        val ys = listOf(-100f, 0f, h / 2f, h * 0.9f, h, h + 100f)
        for (x in xs) {
            for (y in ys) {
                assertEquals(
                    "disabled 在 ($x,$y) 必须无动作",
                    TapAction.NONE,
                    ReaderTapZoneMath.resolve(ReaderTapZone.DISABLED, x, y, w, h),
                )
            }
        }
    }

    @Test
    fun disabledIsNotConfusedWithMenu() {
        // 同一坐标下三栏模式是「中间 → 菜单」，禁用模式必须完全无动作
        assertEquals(TapAction.MENU, at(ReaderTapZone.DEFAULT, w / 2f))
        assertEquals(TapAction.NONE, at(ReaderTapZone.DISABLED, w / 2f))
    }

    // ---------- 其余区域模式不能被改坏 ----------

    @Test
    fun defaultZoneHasThreeBands() {
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.DEFAULT, 10f))
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.DEFAULT, w / 3f - 1f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.DEFAULT, w / 3f + 1f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.DEFAULT, w / 2f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.DEFAULT, w * 2f / 3f - 1f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.DEFAULT, w * 2f / 3f + 1f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.DEFAULT, w - 1f))
    }

    @Test
    fun edgeZoneUsesOuterTwentyPercent() {
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.EDGE, w * 0.1f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.EDGE, w * 0.21f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.EDGE, w / 2f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.EDGE, w * 0.79f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.EDGE, w * 0.95f))
    }

    @Test
    fun kindleZoneUsesOuterQuarter() {
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.KINDLE, w * 0.2f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.KINDLE, w * 0.26f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.KINDLE, w / 2f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.KINDLE, w * 0.9f))
    }

    @Test
    fun lShapedZoneTurnsAtBottomRow() {
        // 底部 15% 是整条翻页区：左半边上一页、右半边下一页，与横向位置无关
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.L_SHAPED, w * 0.05f, h * 0.9f))
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.L_SHAPED, w * 0.49f, h * 0.9f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.L_SHAPED, w * 0.51f, h * 0.9f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.L_SHAPED, w * 0.95f, h * 0.9f))
        // 底部条之外仍是三栏
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.L_SHAPED, 10f, h * 0.2f))
        assertEquals(TapAction.MENU, at(ReaderTapZone.L_SHAPED, w / 2f, h * 0.2f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.L_SHAPED, w - 10f, h * 0.2f))
    }

    @Test
    fun rightLeftZoneSplitsInHalfWithoutMenu() {
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.RIGHT_LEFT, 0f))
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.RIGHT_LEFT, w / 2f - 1f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.RIGHT_LEFT, w / 2f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.RIGHT_LEFT, w))
    }

    // ---------- 边界与异常输入 ----------

    @Test
    fun outOfBoundsCoordinatesAreClamped() {
        // 手指划出屏幕外时不能出现「左边变右边」这类翻转
        assertEquals(TapAction.BACKWARD, at(ReaderTapZone.DEFAULT, -50f))
        assertEquals(TapAction.FORWARD, at(ReaderTapZone.DEFAULT, w + 50f))
    }

    @Test
    fun degenerateViewportDoesNothing() {
        assertEquals(TapAction.NONE, ReaderTapZoneMath.resolve(ReaderTapZone.DEFAULT, 10f, 10f, 0f, h))
        assertEquals(TapAction.NONE, ReaderTapZoneMath.resolve(ReaderTapZone.DEFAULT, 10f, 10f, w, 0f))
        assertEquals(TapAction.NONE, ReaderTapZoneMath.resolve(ReaderTapZone.DEFAULT, 10f, 10f, -1f, -1f))
    }
}
