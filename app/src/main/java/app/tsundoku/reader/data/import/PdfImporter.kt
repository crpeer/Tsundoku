package app.tsundoku.reader.data.import

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** PDF 导入：读取页数并渲染首页作为封面。 */
object PdfImporter {

    data class Result(val pageCount: Int, val coverFile: File?)

    suspend fun inspect(pdfFile: File, coverOut: File): Result = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val count = renderer.pageCount
                    val cover = if (count > 0) renderCover(renderer, coverOut) else null
                    Result(pageCount = count, coverFile = cover)
                }
            }
        }.getOrElse { Result(pageCount = 0, coverFile = null) }
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
