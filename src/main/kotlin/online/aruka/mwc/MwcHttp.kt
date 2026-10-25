package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JDK 標準 com.sun.net.httpserver ベースの HTTP サーバー。
 *
 * base-path 対応: context は (base + "/api") と (base) の 2 つだけ登録する。
 * base 外のパス(例: base=/admin/mc のとき /api/status)はどの context にも
 * 一致しないため 404 になる (HttpServer の既定挙動)。
 *
 * 認証ミドルウェア:
 *  - GET {base}/api/login 以外はセッション Cookie 必須 (無効/なしは 401)
 *  - POST/PUT/DELETE はヘッダ X-MWC-Request: mwc 必須 (CSRF 対策、無ければ 403)
 */
class MwcHttp(private val plugin: MwcPlugin, private val config: MwcConfig, private val auth: Auth) {

    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "mwc-http").apply { isDaemon = true }
    }

    /** "METHOD route" -> handler。M1..M7 で拡張。 */
    val handlers = HashMap<String, (HttpExchange, String) -> Unit>()

    fun start() {
        val s = HttpServer.create(InetSocketAddress(config.host, config.port), 0)
        s.executor = executor
        val apiPrefix = if (config.basePath.isEmpty()) "/api" else "${config.basePath}/api"
        val rootPrefix = if (config.basePath.isEmpty()) "/" else config.basePath
        s.createContext(apiPrefix, ::handleApi)
        s.createContext(rootPrefix, ::handleRoot)
        s.start()
        server = s
        plugin.logger.info("MWC listening on http://${config.host}:${config.port}${config.basePath}/ (api context: $apiPrefix)")
    }

    fun stop() {
        try {
            server?.stop(1)
            executor.shutdownNow()
        } catch (_: Exception) {
        }
        server = null
    }

    // ---------- routing ----------

    private fun handleApi(ex: HttpExchange) {
        try {
            val method = ex.requestMethod
            // CSRF: 状態変更リクエスト (login を含む) はヘッダ必須。セッション確認より先に行う。
            if (method != "GET" && method != "HEAD" && !auth.isCsrfOk(ex)) {
                send(ex, 403, "application/json", """{"error":"missing csrf header"}""")
                return
            }
            val apiPrefix = if (config.basePath.isEmpty()) "/api" else "${config.basePath}/api"
            val route = ex.requestURI.rawPath.removePrefix(apiPrefix).trimStart('/')
            if (method == "POST" && route == "login") {
                handleLogin(ex)
                return
            }
            handleAuthed(ex, method, route)
        } catch (e: MainThread.Unavailable) {
            // サーバーの停止中 (再起動の直後に画面が状態を取りに来たときなど)。異常ではない
            trySend(ex, 503, """{"error":"server is stopping"}""")
        } catch (e: java.io.IOException) {
            // クライアントの切断 (アップロードの中断、応答前にタブを閉じた等)。サーバーの異常ではない
            plugin.logger.info("MWC: request aborted: ${ex.requestMethod} ${ex.requestURI.rawPath}: ${e.javaClass.simpleName}: ${e.message}")
            trySend(ex, 500, """{"error":"request aborted"}""")
        } catch (e: Exception) {
            plugin.logger.severe("MWC: unhandled API error: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(5).forEach { plugin.logger.severe("    at $it") }
            trySend(ex, 500, """{"error":"internal error"}""")
        }
    }

    /** 応答を試みる。相手が切断済みなら何もしない */
    private fun trySend(ex: HttpExchange, code: Int, body: String) {
        try {
            send(ex, code, "application/json", body)
        } catch (_: Exception) {
            ex.close()
        }
    }

    private fun handleAuthed(ex: HttpExchange, method: String, route: String) {
        if (!auth.checkSession(ex.requestHeaders.getFirst("Cookie"))) {
            send(ex, 401, "application/json", """{"error":"unauthorized"}""")
            return
        }
        // (CSRF チェックは handleApi 側で全リクエストに先に行う)
        val h = handlers["$method $route"]
        if (h == null) {
            send(ex, 404, "application/json", """{"error":"not found"}""")
            return
        }
        h(ex, route)
    }

    /** 静的ファイル・index.html。静的ファイルは機密を含まないため認証不要 (API は認証必須)。 */
    private fun handleRoot(ex: HttpExchange) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") {
            send(ex, 405, "text/plain", "method not allowed")
            return
        }
        val p = ex.requestURI.rawPath
        // 末尾スラッシュなし (例: /admin/mc) のとき、ブラウザの相対パスは親ディレクトリ基準で
        // 解決されて壊れるため {base}/ へリダイレクトする
        if (config.basePath.isNotEmpty() && p == config.basePath) {
            ex.responseHeaders.add("Location", "${config.basePath}/")
            send(ex, 301, "text/plain", "")
            return
        }
        val path = if (config.basePath.isEmpty()) p else p.removePrefix(config.basePath)
        when {
            path.isEmpty() || path == "/" || path == "/index.html" -> serveIndex(ex)
            path.startsWith("/static/") -> serveStatic(ex, path.removePrefix("/static/"))
            else -> send(ex, 404, "application/json", """{"error":"not found"}""")
        }
    }

    private fun handleLogin(ex: HttpExchange) {
        val ip = ex.remoteAddress?.address?.hostAddress ?: "unknown"
        if (!auth.loginAllowed(ip)) {
            send(ex, 429, "application/json", """{"error":"rate limited"}""")
            return
        }
        val body = readBody(ex, 4096)
        if (body != null && auth.verifyLoginBody(body)) {
            auth.loginSucceeded(ip)
            val session = auth.newSession()
            ex.responseHeaders.add("Set-Cookie", auth.cookieValue(session, config.basePath))
            send(ex, 200, "application/json", """{"ok":true}""")
        } else {
            auth.loginFailed(ip)
            send(ex, 401, "application/json", """{"error":"invalid token"}""")
        }
    }

    // ---------- static ----------

    private val staticWhitelist = mapOf(
        "app.js" to "text/javascript; charset=utf-8",
        "app.css" to "text/css; charset=utf-8"
    )

    private fun serveIndex(ex: HttpExchange) {
        val html = plugin.resourceString("web/index.html")
        if (html == null) {
            send(ex, 500, "text/plain", "index.html missing from jar")
            return
        }
        send(ex, 200, "text/html; charset=utf-8", html)
    }

    private fun serveStatic(ex: HttpExchange, name: String) {
        // パス区切り・.. を含む名前は拒否 (ホワイトリスト + 単一セグメントのみ)
        if (name.contains('/') || name.contains('\\') || name.contains("..") || name.startsWith('.')) {
            send(ex, 404, "application/json", """{"error":"not found"}""")
            return
        }
        val ct = staticWhitelist[name]
        if (ct == null) {
            send(ex, 404, "application/json", """{"error":"not found"}""")
            return
        }
        val content = plugin.resourceString("web/$name")
        if (content == null) {
            send(ex, 500, "text/plain", "$name missing from jar")
            return
        }
        send(ex, 200, ct, content)
    }
}

// ---------- shared helpers ----------

fun send(ex: HttpExchange, code: Int, contentType: String, body: String) {
    val bytes = body.toByteArray()
    ex.responseHeaders.add("Content-Type", contentType)
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(code, if (code == 204 || body.isEmpty()) -1L else bytes.size.toLong())
    if (code != 204 && body.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    ex.close()
}

/** リクエストボディを文字列で読む (最大 [limit] バイト。超過は 413 相当の null) */
fun readBody(ex: HttpExchange, limit: Long): String? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    try {
        while (true) {
            val n = ex.requestBody.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
    } catch (_: Exception) {
        return null
    }
    return out.toString(Charsets.UTF_8)
}

/** jar 内リソースを文字列で読む */
fun org.bukkit.plugin.java.JavaPlugin.resourceString(name: String): String? =
    getResource(name)?.use { it.readBytes().toString(Charsets.UTF_8) }

/** Bukkit API はメインスレッドで呼ぶためのブリッジ (HTTP スレッドから呼ぶ)。 */
object MainThread {
    /** 停止中などでメインスレッドが処理を受け付けない (HTTP は 503 を返す) */
    class Unavailable(msg: String) : RuntimeException(msg)

    fun <T> call(plugin: org.bukkit.plugin.java.JavaPlugin, timeoutMs: Long, action: () -> T): T {
        if (org.bukkit.Bukkit.isPrimaryThread()) return action()
        if (!plugin.isEnabled || org.bukkit.Bukkit.isStopping()) throw Unavailable("server is stopping")
        val resultRef = java.util.concurrent.atomic.AtomicReference<Any?>()
        val errorRef = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    resultRef.set(action())
                } catch (t: Throwable) {
                    errorRef.set(t)
                } finally {
                    latch.countDown()
                }
            })
        } catch (e: org.bukkit.plugin.IllegalPluginAccessException) {
            throw Unavailable("plugin is disabled")
        }
        val done = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // onDisable で HTTP のスレッドが止められた
            Thread.currentThread().interrupt()
            throw Unavailable("interrupted while the server is stopping")
        }
        if (!done) {
            if (org.bukkit.Bukkit.isStopping()) throw Unavailable("server is stopping")
            throw java.util.concurrent.TimeoutException("main thread task timed out after ${timeoutMs}ms")
        }
        errorRef.get()?.let { throw RuntimeException("main thread task failed", it) }
        @Suppress("UNCHECKED_CAST")
        return resultRef.get() as T
    }
}
