package app.tsundoku.reader

import app.tsundoku.reader.ui.reader.PendingFindQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索定位请求的排队。
 *
 * 回归的是这个缺陷：切章后 WebView 还没加载完、内嵌脚本还没建立，
 * 原来的实现「试着调用一次然后立刻消费请求」，于是定位被静默丢弃。
 */
class PendingFindQueueTest {

    @Test
    fun requestIsKeptUntilThePageIsReady() {
        val queue = PendingFindQueue()
        queue.enqueue("龙族")

        // WebView 未就绪：不执行，但请求必须还在
        assertNull(queue.take(ready = false))
        assertTrue(queue.hasPending)
        assertEquals(0, queue.deliveredCount)

        // 就绪后才执行，且只执行一次
        assertEquals("龙族", queue.take(ready = true))
        assertFalse(queue.hasPending)
        assertEquals(1, queue.deliveredCount)

        assertNull("同一个请求不能重复执行", queue.take(ready = true))
        assertEquals(1, queue.deliveredCount)
    }

    @Test
    fun repeatedSearchesForTheSameTextStillQueueAgain() {
        val queue = PendingFindQueue()
        queue.enqueue("路明非")
        assertEquals("路明非", queue.take(ready = true))

        // 用户又搜了一次同样的关键字（换了一章）：必须能再次执行
        queue.enqueue("路明非")
        assertEquals("路明非", queue.take(ready = true))
        assertEquals(2, queue.deliveredCount)
    }

    @Test
    fun latestRequestWinsWhenUserSearchesQuickly() {
        val queue = PendingFindQueue()
        queue.enqueue("第一处")
        queue.enqueue("第二处")
        assertEquals("第二处", queue.take(ready = true))
        assertEquals(1, queue.deliveredCount)
    }

    @Test
    fun blankRequestIsIgnored() {
        val queue = PendingFindQueue()
        queue.enqueue("")
        queue.enqueue("   ")
        assertFalse(queue.hasPending)
        assertNull(queue.take(ready = true))
    }

    @Test
    fun clearDropsQueuedRequest() {
        val queue = PendingFindQueue()
        queue.enqueue("残留")
        queue.clear()
        assertFalse(queue.hasPending)
        assertNull(queue.take(ready = true))
    }
}
