package app.tsundoku.reader.data.import

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * PDF 导入：读取页数并渲染首页作为封面。
 *
 * 失败必须抛出（而不是返回 pageCount = 0），否则损坏/加密的 PDF 会被当成导入成功。
 */
object PdfImporter {

    data class Result(val pageCount: Int, val coverFile: File?)

    suspend fun inspect(pdfFile: File, coverOut: File): Result = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            throw IOException("PDF 文件为空或不存在")
        }
        val descriptor = try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            throw IOException("无法打开 PDF：${error.message ?: error.javaClass.simpleName}", error)
        }
        descriptor.use { fd ->
            val renderer = try {
                PdfRenderer(fd)
            } catch (error: Exception) {
                throw IOException(
                    "PDF 解析失败，可能已损坏或加密：${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
            renderer.use {
                val count = it.pageCount
                if (count <= 0) throw IOException("PDF 没有可用页面")
                Result(pageCount = count, coverFile = renderCover(it, coverOut))
            }
        }
    }

    private fun renderCover(renderer: PdfRenderer, coverOut: File): File? = runCatching {
        renderer.openPage(0).use { page ->
            val maxEdge = 720f
            val scale = minOf(maxEdge / page.width, maxEdge / page.height, 2f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            coverOut.parentFile?.mkdirs()
            coverOut.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            bitmap.recycle()
        }
        coverOut
    }.getOrNull()
}
