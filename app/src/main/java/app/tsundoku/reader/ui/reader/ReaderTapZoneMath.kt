package app.tsundoku.reader.ui.reader

import app.tsundoku.reader.data.prefs.ReaderTapZone

/** 点击正文后要执行的动作。 */
enum class TapAction {
    /** 什么都不做（点区域被禁用）。 */
    NONE,

    /** 中间区域：显隐上下栏。 */
    MENU,

    /** 上一页。 */
    BACKWARD,

    /** 下一页。 */
    FORWARD,
}

/**
 * 点击区域判定（参考 Mihon 的 NavigationMode）。
 *
 * 这段逻辑放在 Kotlin 而不是内嵌脚本里，原因有两个：
 * 1. 内嵌脚本无法在 Gradle 单元测试里执行，判定错误没有任何自动化手段能拦住；
 * 2. 脚本里的 `window.innerWidth` 实测不可靠（同一设备不同章节会给出 412 / 1628），
 *    而这里用的宽高就是 Kotlin 侧量到、并通过 viewport meta 固定下来的值。
 *
 * 坐标系：CSS 像素，原点在正文左上角。
 */
object ReaderTapZoneMath {

    fun resolve(
        zone: ReaderTapZone,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): TapAction {
        // 禁用状态必须早于任何几何判断，否则会被当成「中间区域」
        if (zone == ReaderTapZone.DISABLED) return TapAction.NONE
        if (width <= 0f || height <= 0f) return TapAction.NONE

        val cx = x.coerceIn(0f, width)
        val left = cx
        val right = width - cx

        return when (zone) {
            // 左右两栏只有翻页，没有中间区域（与 Mihon 的 RIGHT_AND_LEFT 一致）
            ReaderTapZone.RIGHT_LEFT ->
                if (cx < width / 2f) TapAction.BACKWARD else TapAction.FORWARD

            ReaderTapZone.EDGE -> when {
                left < width * 0.2f -> TapAction.BACKWARD
                right < width * 0.2f -> TapAction.FORWARD
                else -> TapAction.MENU
            }

            ReaderTapZone.KINDLE -> when {
                left < width * 0.25f -> TapAction.BACKWARD
                right < width * 0.25f -> TapAction.FORWARD
                else -> TapAction.MENU
            }

            ReaderTapZone.L_SHAPED -> when {
                // 底部整条：左半边上一页、右半边下一页（单手可达）
                y > height * 0.85f ->
                    if (cx < width / 2f) TapAction.BACKWARD else TapAction.FORWARD
                left < width / 3f -> TapAction.BACKWARD
                right < width / 3f -> TapAction.FORWARD
                else -> TapAction.MENU
            }

            ReaderTapZone.DEFAULT -> when {
                left < width / 3f -> TapAction.BACKWARD
                right < width / 3f -> TapAction.FORWARD
                else -> TapAction.MENU
            }

            ReaderTapZone.DISABLED -> TapAction.NONE
        }
    }
}

/** 一次正文点击最终要执行的结果。 */
internal sealed interface TapResult {

    /** 命中了某条高亮：打开该批注的菜单（编辑笔记 / 删除）。 */
    data class Highlight(val id: String) : TapResult

    /** 没命中高亮：按点击区域执行翻页或显隐工具栏。 */
    data class Action(val action: TapAction) : TapResult
}

/**
 * 正文点击的完整决策：**先判高亮命中，再判点击区域**。
 *
 * 顺序不能反。点在高亮上应该打开批注，而不是顺手翻页或显隐工具栏；
 * 判断依据是 [AnnotationHitTest] 给出的 (节点路径, 偏移)，与渲染高亮用的是同一份
 * [AnnotationRange]，因此不存在 `Range.intersectsNode()` 那种「同节点内点未高亮文字也命中」的问题。
 *
 * 单独抽成一个函数，是为了让「命中优先」「未命中不弹菜单」「取不到点击位置时什么都不做」
 * 这些顺序要求在单元测试里可验证，而不是只能靠在 WebView 里手点。
 */
internal object ReaderTapRouter {

    fun route(
        ranges: List<AnnotationRange>,
        path: String,
        offset: Int,
        zone: ReaderTapZone,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): TapResult {
        // path/offset 取不到时 hit() 返回 null（offset < 0 或 path 为空），不会误弹菜单
        val hit = AnnotationHitTest.hit(ranges, path, offset)
        if (hit != null) return TapResult.Highlight(hit.id)
        return TapResult.Action(ReaderTapZoneMath.resolve(zone, x, y, width, height))
    }
}
