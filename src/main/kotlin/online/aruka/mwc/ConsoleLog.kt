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
 * Appender の中からはログを出さない (Log4j が "Recursive call to appender" の ERROR を出す)。
 */
class ConsoleLog(private val plugin: MwcPlugin) {

    // log4j-core 2.17.1 (コンパイル結果で確認): 抽象メソッドは append。コンストラクタは 4 引数。
    private val appender = object : AbstractAppender(APPENDER_NAME, null, null, true) {
        override fun append(event: LogEvent) {
            try {
                val msg = event.message?.formattedMessage ?: return
                onLine(format(event, msg))
            } catch (_: Throwable) {
                // メインのログ出力には影響させない
            }
        }
    }

    private val captureFile: File? =
        if (plugin.config.captureFile.isBlank()) null else File(plugin.dataFolder, plugin.config.captureFile)

    private var captureWriter: BufferedWriter? = null
    private var captureSize = 0L

    // リングバッファ (ANSI 除去済みの行を古い順に保持)
    private val buffer = ArrayDeque<String>()
    @Volatile
    private var capacity: Int = 1000

    // SSE 購読者 (行を非ブロッキングで受け取る)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** ルートロガーに Appender を付与する (onEnable で呼ぶ) */
    fun attach() {
        capacity = plugin.config.consoleBufferSize.coerceAtLeast(1)
        appender.start()
        val root = rootLoggerConfig()
        if (root == null) {
            plugin.logger.warning("MWC: Log4j core not found; the web console will not show server logs")
            return
        }
        root.addAppender(appender, null, null)
        plugin.logger.info("MWC: console capture attached (buffer ${capacity} lines)")
    }

    /** Appender を外してファイルを閉じる (onDisable で必ず呼ぶ) */
    fun detach() {
        listeners.clear()
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

    /**
     * 購読を開始する。バッファの写しと購読登録はアトミックに行う
     * (写し取りと登録の間の行は重複も欠落もしない)。
     * 戻り値は (バッファの写し, 購読解除関数)
     */
    fun subscribeWithSnapshot(onLine: (String) -> Unit): Pair<List<String>, () -> Unit> =
        synchronized(buffer) {
            val snap = buffer.toList()
            val unsub: () -> Unit = { listeners.remove(onLine) }
            listeners.add(onLine)
            snap to unsub
        }

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
        for (l in listeners) {
            try {
                l(line)
            } catch (_: Throwable) {
                // 購読者の異常はログ取得全体に影響させない
            }
        }
        writeCapture(line)
    }

    /**
     * サーバーのコンソールに近い形にする: `[HH:mm:ss LEVEL]: [プラグイン名] 本文`。
     * 例外が付いていれば、スタックトレースを続けて付ける (先頭 [MAX_TRACE] 行)。
     */
    private fun format(event: LogEvent, msg: String): String {
        val sb = StringBuilder()
        sb.append('[').append(TIME.format(java.time.Instant.ofEpochMilli(event.timeMillis)))
            .append(' ').append(event.level?.name() ?: "INFO").append("]: ")
        // プラグインのロガー名 (例 "MWC") だけを付ける。Minecraft 本体のロガー名 (パッケージ名) は付けない
        val name = event.loggerName.orEmpty()
        if (name.isNotEmpty() && '.' !in name && name != "Minecraft") sb.append('[').append(name).append("] ")
        sb.append(msg)
        event.thrown?.let { t ->
            val lines = t.stackTraceToString().trimEnd().lines()
            for (l in lines.take(MAX_TRACE)) sb.append('\n').append(l)
            if (lines.size > MAX_TRACE) sb.append("\n\t... ").append(lines.size - MAX_TRACE).append(" more")
        }
        return sb.toString()
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
        private const val MAX_TRACE = 40
        private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault())

        // CSI (\x1B[ ... 終端文字) / OSC (\x1B] ... BEL | \x1B\) / 単一エスケープ (\x1B X)
        private val ANSI_RE = Regex(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\-_]"
        )
    }
}
