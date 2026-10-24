package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * GET {base}/api/console — コンソールログの SSE 配信。
 *
 * 接続時の流れ (ハンドラスレッドがブロッキングで配信):
 *  1. ヘッダ (text/event-stream) を送信
 *  2. リングバッファの全行を `data:` イベントとして送信
 *  3. その後のログ行をライブ配信 (購読者ごとに有界キュー。満杯なら古い行を捨てる)
 *  4. 10 秒間行がなければ keepalive コメント (`: keepalive`)
 *
 * バッファの写しと購読登録はアトミックに行うため、行の重複・欠落は起きない。
 * クライアント切断 (書き込み例外) で購読を解除して終了する。
 * ハンドラは HTTP executor のキャッシュスレッドプールの 1 スレッドを占有する (最小構成のため許容)。
 */
class ConsoleSseHandler(private val consoleLog: ConsoleLog) {

    fun handle(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.sendResponseHeaders(200, 0L)

        val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
        val (buffer, unsubscribe) = consoleLog.subscribeWithSnapshot { line ->
            // 非ブロッキング。遅いクライアントのせいでログ取得 (メインスレッド含む) を止めない
            while (!queue.offer(line)) queue.poll()
        }
        val out = ex.responseBody
        try {
            for (line in buffer) {
                writeEvent(out, line)
            }
            out.flush()
            var last = System.currentTimeMillis()
            while (true) {
                val line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (line != null) {
                    writeEvent(out, line)
                    out.flush()
                    last = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - last >= KEEPALIVE_MS) {
                    out.write(KEEPALIVE.toByteArray())
                    out.flush()
                    last = System.currentTimeMillis()
                }
            }
        } catch (_: Exception) {
            // クライアント切断 / サーバー停止
        } finally {
            unsubscribe()
            try {
                out.close()
            } catch (_: Exception) {
            }
            ex.close()
        }
    }

    private fun writeEvent(out: java.io.OutputStream, line: String) {
        for (frag in line.split("\n")) {
            out.write("data: $frag\n".toByteArray())
        }
        out.write("\n".toByteArray())
    }

    companion object {
        private const val QUEUE_CAPACITY = 512
        private const val POLL_MS = 1000L
        private const val KEEPALIVE_MS = 10_000L
        private const val KEEPALIVE = ": keepalive\n\n"
    }
}
