package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import com.sun.net.httpserver.HttpExchange

/**
 * 認証: 初回生成トークン + Cookie セッション + ログインレート制限 + CSRF ヘッダ。
 *
 * - トークンは plugins/MWC/token.key に永続化 (権限 600)。初回生成時のみコンソールに表示。
 * - login 成功で HttpOnly / SameSite=Strict の Cookie (Path = base-path) を発行。
 * - 状態変更メソッド (POST/PUT/DELETE) はヘッダ X-MWC-Request: mwc を要求 (CSRF 対策)。
 * - ログイン失敗を IP ごとにカウント。60 秒以内に 5 回失敗すると 60 秒ブロック。
 */
class Auth(private val plugin: MwcPlugin) {

    private val random = SecureRandom()
    private val gson = Gson()
    private val tokenFile = File(plugin.dataFolder, "token.key")

    var token: String = ""
        private set

    /** cookieToken -> 有効期限 (ms) */
    private val sessions = ConcurrentHashMap<String, Long>()
    private val sessionTtlMs = 12L * 60 * 60 * 1000

    // レート制限: ip -> (直近失敗の時刻 ms, ブロック解除時刻 ms)
    private val failTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val maxFailures = 5
    private val windowMs = 60_000L
    private val blockMs = 60_000L

    fun init() {
        if (tokenFile.exists()) {
            token = tokenFile.readText().trim()
            if (token.isEmpty()) throw IllegalStateException("MWC token file is empty: $tokenFile")
            plugin.logger.info("MWC token loaded from ${tokenFile.name}")
        } else {
            token = generateToken()
            tokenFile.parentFile.mkdirs()
            tokenFile.writeText("$token\n")
            try {
                tokenFile.setReadable(false, false)
                tokenFile.setReadable(true, true)
                tokenFile.setWritable(false, false)
                tokenFile.setWritable(true, true)
            } catch (_: Exception) {
                // ファイルシステムが owner 権限制御をしない場合 (無視して進める)
            }
            // 一度だけコンソールに表示 (このファイルは保護対象なので Web からは読めない)
            plugin.logger.info("MWC token: $token")
            plugin.logger.info("MWC: copy this token now. It will NOT be shown again (stored in ${tokenFile.name}).")
        }
    }

    fun verifyLoginBody(body: String): Boolean {
        try {
            val obj = gson.fromJson(body, JsonObject::class.java) ?: return false
            val t = obj.get("token")?.asString ?: return false
            return constantTimeEquals(t, token)
        } catch (_: Exception) {
            return false
        }
    }

    /** login 成功 -> 新しいセッション Cookie の値を返す */
    fun newSession(): String {
        val t = randomToken()
        sessions[t] = System.currentTimeMillis() + sessionTtlMs
        return t
    }

    /** Cookie からセッションを検証 (有効なら TTL 更新して true) */
    fun checkSession(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val m = CookiePattern.find(cookieHeader) ?: return false
        val sessionToken = m.groupValues[1]
        val now = System.currentTimeMillis()
        val exp = sessions[sessionToken] ?: return false
        if (exp < now) {
            sessions.remove(sessionToken)
            return false
        }
        sessions[sessionToken] = now + sessionTtlMs
        return true
    }

    /** レート制限: true = リクエストを継続してよい, false = ブロック中 */
    fun loginAllowed(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val until = blockedUntil[ip] ?: return true
        if (until <= now) {
            blockedUntil.remove(ip)
            failTimes.remove(ip)
            return true
        }
        return false
    }

    fun loginFailed(ip: String) {
        val now = System.currentTimeMillis()
        val list = failTimes.getOrPut(ip) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > windowMs }
            list.add(now)
            if (list.size >= maxFailures) {
                blockedUntil[ip] = now + blockMs
                list.clear()
                plugin.logger.warning("MWC: login rate limit triggered for $ip (blocked ${blockMs / 1000}s)")
            }
        }
    }

    fun loginSucceeded(ip: String) {
        failTimes.remove(ip)
        blockedUntil.remove(ip)
    }

    fun cookieValue(session: String, basePath: String): String {
        val path = if (basePath.isEmpty()) "/" else basePath
        return "MWC_SESSION=$session; Path=$path; HttpOnly; SameSite=Strict"
    }

    /** CSRF: 状態変更メソッドに要求するヘッダ */
    fun isCsrfOk(exchange: HttpExchange): Boolean =
        "mwc".equals(exchange.requestHeaders.getFirst(CSRF_HEADER), ignoreCase = true)

    fun audit(action: String, path: String, sizeBytes: Long? = null) {
        val size = if (sizeBytes != null) "${sizeBytes}B" else "-"
        plugin.logger.info("MWC audit: $action path=$path size=$size (session=${sessions.keys.size} active)")
    }

    fun commandAudit(command: String) {
        plugin.logger.info("MWC audit: command sent: $command")
    }

    private fun generateToken(): String {
        val b = ByteArray(32)
        random.nextBytes(b)
        return b.encodeToHexString()
    }

    private fun randomToken(): String {
        val b = ByteArray(32)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.encodeToHexString(): String = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    companion object {
        const val CSRF_HEADER = "X-MWC-Request"
        private val CookiePattern = Regex("MWC_SESSION=([^;\\s]+)")
    }
}
