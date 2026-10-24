package online.aruka.mwc

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * コンソールログの取得: Log4j2 の Appender をルートロガーに付与してログ行を受け取る。
 *
 * 受け取った行は ANSI エスケープを除去してリングバッファに保持し、
 * 設定 `console.capture-file` が指定されていれば同様にファイルに追記する
 * (上限 1MB になったら切り捨て)。
 *
 * 無限再帰に注意: plugin.logger からの出力はこの Appender にも届くが、
 * 「最初の行だけ 1 回」のログは再帰しない (loggedFirst が 2 回目で true になる)。
 */
class ConsoleLog(private val plugin: MwcPlugin) {

    // log4j-core 2.17.1 (コンパイル結果で確認): 抽象メソッドは append。コンストラクタは 4 引数。
    private val appender = object : AbstractAppender(APPENDER_NAME, null, null, true) {
        override fun append(event: LogEvent) {
            try {
                val msg = event.message?.formattedMessage ?: return
                onLine(msg)
            } catch (_: Throwable) {
                // メインのログ出力には影響させない
            }
        }
    }

    private val captureFile: File? =
        if (plugin.config.captureFile.isBlank()) null else File(plugin.dataFolder, plugin.config.captureFile)

    private var captureWriter: BufferedWriter? = null
    private var captureSize = 0L

    // リングバッファ (ANSI 除去済みの行を古い順に保持。SSE 配信は次のステップ)
    private val buffer = ArrayDeque<String>()
    @Volatile
    private var capacity: Int = 1000

    @Volatile
    private var loggedFirst = false

    /** ルートロガーに Appender を付与する (onEnable で呼ぶ) */
    fun attach() {
        capacity = plugin.config.consoleBufferSize.coerceAtLeast(1)
        appender.start()
        rootLoggerConfig()?.addAppender(appender, null, null)
    }

    /** Appender を外してファイルを閉じる (onDisable で必ず呼ぶ) */
    fun detach() {
        try {
            rootLoggerConfig()?.removeAppender(APPENDER_NAME)
            appender.stop()
        } catch (_: Throwable) {
        }
        try {
            captureWriter?.close()
        } catch (_: Exception) {
        }
        captureWriter = null
    }

    /** ルートの LoggerConfig (Appender を付ける対象) */
    private fun rootLoggerConfig(): org.apache.logging.log4j.core.config.LoggerConfig? =
        (LogManager.getContext(false) as? org.apache.logging.log4j.core.LoggerContext)
            ?.configuration?.rootLogger

    /** リングバッファの写し (古い順)。SSE 接続時の初期送信に使う */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /** 1 行をバッファに追加 (ANSI 除去済みのはずだが念のため除去して保持) */
    private fun addLine(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    private fun onLine(msg: String) {
        val line = stripAnsi(msg)
        addLine(line)
        if (!loggedFirst) {
            loggedFirst = true
            val preview = line.take(80).replace("\n", "\\n")
            plugin.logger.info("MWC: console capture active (first line: $preview)")
        }
        writeCapture(line)
    }

    /** ANSI エスケープシーケンス (CSI / OSC / 単一エスケープ) を除去する */
    private fun stripAnsi(s: String): String = if ('\u001B' in s) ANSI_RE.replace(s, "") else s

    private fun writeCapture(msg: String) {
        val f = captureFile ?: return
        try {
            if (captureWriter == null) {
                f.parentFile?.mkdirs()
                captureWriter = BufferedWriter(FileWriter(f, true))
                captureSize = f.length()
            }
            if (captureSize > MAX_CAPTURE) {
                // 上限を超えたら切り捨て (デバッグ用のキャプチャなので単純にtruncate)
                captureWriter?.close()
                captureWriter = BufferedWriter(FileWriter(f, false))
                captureSize = 0
            }
            val w = captureWriter ?: return
            w.write(msg)
            w.write("\n")
            w.flush()
            captureSize += msg.length + 1
        } catch (_: Exception) {
            // キャプチャ失敗は無視 (メイン機能ではない)
        }
    }

    companion object {
        private const val APPENDER_NAME = "MwcConsoleCapture"
        private const val MAX_CAPTURE = 1L * 1024 * 1024

        // CSI (\x1B[ ... 終端文字) / OSC (\x1B] ... BEL | \x1B\) / 単一エスケープ (\x1B X)
        private val ANSI_RE = Regex(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\-_]"
        )
    }
}
