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
 * ステップ1(最小): 受け取った行を、設定 `console.capture-file` が指定されていれば
 * プラグインディレクトリ内のファイルに追記し(上限 1MB になったら切り捨て)、
 * 最初の行だけサーバーログに記録する。
 * (リングバッファ / SSE 配信は次のステップで追加する)
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

    @Volatile
    private var loggedFirst = false

    /** ルートロガーに Appender を付与する (onEnable で呼ぶ) */
    fun attach() {
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

    private fun onLine(msg: String) {
        if (!loggedFirst) {
            loggedFirst = true
            val preview = msg.take(80).replace("\n", "\\n")
            plugin.logger.info("MWC: console capture active (first line: $preview)")
        }
        writeCapture(msg)
    }

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
    }
}
