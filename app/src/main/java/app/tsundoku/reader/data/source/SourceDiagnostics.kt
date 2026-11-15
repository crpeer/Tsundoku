package app.tsundoku.reader.data.source

import app.tsundoku.reader.domain.model.ChapterItem
import app.tsundoku.reader.domain.model.SearchItem
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 书源诊断：把「搜索 → 详情 → 目录 → 正文」逐段跑一遍并回报结果，
 * 让用户知道到底是哪一步、哪个规则失效，而不是一句「加载失败」。
 */
class SourceDiagnostics(private val sources: SourceManager) {

    enum class Status { OK, FAILED, SKIPPED }

    data class Step(
        val name: String,
        val status: Status,
        val detail: String,
        val millis: Long = 0,
    )

    data class Report(
        val sourceId: Long,
        val sourceName: String,
        val keyword: String,
        val steps: List<Step>,
    ) {
        val okCount: Int get() = steps.count { it.status == Status.OK }
        val failed: Step? get() = steps.firstOrNull { it.status == Status.FAILED }
    }

    suspend fun run(sourceId: Long, keyword: String): Report {
        val info = sources.sourcesOnce().firstOrNull { it.id == sourceId }
        val name = info?.name ?: "未知来源"
        val source = runCatching { sources.sourceById(sourceId) }.getOrNull()
        return runCatching { runPipeline(sourceId, name, source, keyword) }
            .getOrElse { error ->
                Report(sourceId, name, keyword, listOf(Step("载入书源", Status.FAILED, describe(error))))
            }
    }

    private suspend fun runPipeline(
        sourceId: Long,
        name: String,
        source: BookSource?,
        keyword: String,
    ): Report {
        if (source == null) {
            return Report(sourceId, name, keyword, listOf(Step("载入书源", Status.FAILED, "配置无法解析")))
        }
        val steps = mutableListOf<Step>()

        val searchStep = timed("搜索") { source.search(keyword, 1) }
        steps += searchStep.toStep { items -> if (items.isEmpty()) "0 条结果" else "${items.size} 条结果，首条「${items.first().title}」" }
        val firstItem: SearchItem? = searchStep.value?.firstOrNull()
        if (searchStep.error != null) {
            steps += skipped("详情 / 目录 / 正文", "搜索失败，后续跳过")
            return Report(sourceId, name, keyword, steps)
        }
        if (firstItem == null) {
            steps += Step("搜索", Status.OK, "0 条结果（规则可能不匹配或站点改版）")
            steps += skipped("详情 / 目录 / 正文", "没有搜索结果可用于后续检查")
            return Report(sourceId, name, keyword, steps)
        }

        val detailsStep = timed("详情") { source.details(firstItem.url) }
        steps += detailsStep.toStep { details ->
            "标题=${details.title.ifBlank { "空" }}，作者=${details.author ?: "空"}"
        }

        val chaptersStep = timed("目录") { source.chapters(firstItem.url) }
        steps += chaptersStep.toStep { chapters -> summary(chapters) }
        val firstChapter: ChapterItem? = chaptersStep.value?.firstOrNull()
        if (chaptersStep.error != null) {
            steps += skipped("正文", "目录失败，无法挑第一章")
            return Report(sourceId, name, keyword, steps)
        }
        if (firstChapter == null) {
            steps += skipped("正文", "目录为空")
            return Report(sourceId, name, keyword, steps)
        }

        val contentStep = timed("正文") { source.content(firstChapter.url) }
        steps += contentStep.toStep { html ->
            "长度=${html.length} 字符" + if (html.length < 200) "（偏短，规则可能不全）" else ""
        }

        return Report(sourceId, name, keyword, steps)
    }

    private fun summary(chapters: List<ChapterItem>): String {
        if (chapters.isEmpty()) return "0 章"
        val first = chapters.first().title
        val last = chapters.last().title
        val duplicated = chapters.size - chapters.distinctBy { it.url }.size
        return buildString {
            append("${chapters.size} 章，首=「$first」，末=「$last」")
            if (duplicated > 0) append("，重复地址 $duplicated 条")
        }
    }

    private class Timed<T>(
        val label: String,
        val value: T?,
        val error: Throwable?,
        val millis: Long,
    )

    private suspend fun <T> timed(name: String, block: suspend () -> T): Timed<T> {
        val start = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            runCatching { block() }
        }
        val millis = System.currentTimeMillis() - start
        if (outcome == null) {
            return Timed(name, null, SourceException("$name 超时（>${STEP_TIMEOUT_MS / 1000}s）"), millis)
        }
        return Timed(name, outcome.getOrNull(), outcome.exceptionOrNull(), millis)
    }

    private fun <T> Timed<T>.toStep(describe: (T) -> String): Step {
        val failure = error
        return if (failure != null) {
            Step(label, Status.FAILED, describe(failure), millis)
        } else {
            Step(label, Status.OK, value?.let(describe).orEmpty(), millis)
        }
    }

    private fun skipped(name: String, reason: String) = Step(name, Status.SKIPPED, reason)

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        private const val STEP_TIMEOUT_MS = 20_000L
    }
}
