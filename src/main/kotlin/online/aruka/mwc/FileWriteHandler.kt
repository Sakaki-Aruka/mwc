package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * PUT {base}/api/file?path=<rel> — テキストファイルの編集。
 *
 * body: {"content": "...", "etag": "..."}
 *  - etag なし / 不一致 → 409 (競合)
 *  - バイナリ → 415 / 保護対象 → 403 / サイズ超過 → 413
 *  - server.properties は key=value の最低検証 (不正なら 400)
 *  - 保存前に .bak を作成し、一時ファイル → 原子リネームで書き込む
 */
class FileWriteHandler(
    private val fa: FileAccess,
    private val config: MwcConfig,
    private val auth: Auth
) {

    private val gson = Gson()

    fun handle(ex: HttpExchange) {
        val rel = fa.normalizeRel(queryParam(ex, "path"))
        if (rel == null) {
            send(ex, 400, "application/json", """{"error":"bad path"}""")
            return
        }
        try {
            val real = fa.resolve(rel)
            if (real == null) {
                send(ex, 404, "application/json", """{"error":"not found"}""")
                return
            }
            if (!Files.isRegularFile(real)) {
                send(ex, 400, "application/json", """{"error":"not a file"}""")
                return
            }
            if (fa.isProtected(real)) {
                send(ex, 403, "application/json", """{"error":"protected"}""")
                return
            }
            if (!fa.isTextFile(real)) {
                send(ex, 415, "application/json", """{"error":"binary file"}""")
                return
            }

            val body = readBody(ex, MAX_BODY)
            if (body == null) {
                send(ex, 413, "application/json", """{"error":"too large"}""")
                return
            }
            val obj = try {
                gson.fromJson(body, JsonObject::class.java)
            } catch (_: Exception) {
                null
            }
            val content = obj?.get("content")?.takeIf { !it.isJsonNull }?.asString
            if (content == null) {
                send(ex, 400, "application/json", """{"error":"content required"}""")
                return
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > config.maxEditSize) {
                send(ex, 413, "application/json", """{"error":"too large"}""")
                return
            }
            // 読み込み時の ETag ヘッダの値をそのまま受け取る (ブラウザは引用符付きで送る。弱い ETag の W/ も外す)
            val etag = obj.get("etag")?.takeIf { !it.isJsonNull }?.asString
                ?.trim()?.removePrefix("W/")?.removeSurrounding("\"")
            if (etag == null) {
                send(ex, 409, "application/json", """{"error":"etag required"}""")
                return
            }
            if (etag != fa.etag(real)) {
                send(ex, 409, "application/json", """{"error":"modified"}""")
                return
            }
            if (real.fileName.toString().equals("server.properties", ignoreCase = true) &&
                !isValidProperties(content)
            ) {
                send(ex, 400, "application/json", """{"error":"invalid server.properties"}""")
                return
            }

            // 保存前に .bak (同じディレクトリに name.bak)
            val bak = real.resolveSibling(real.fileName.toString() + ".bak")
            Files.copy(real, bak, StandardCopyOption.REPLACE_EXISTING)
            // 一時ファイル → 原子リネーム
            val tmp = Files.createTempFile(real.parent, ".mwc-tmp-", ".tmp")
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, real, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                try {
                    Files.deleteIfExists(tmp)
                } catch (_: Exception) {
                }
            }
            auth.audit(ex, "edit", rel, bytes.size.toLong())
            send(ex, 200, "application/json", """{"ok":true}""")
        } catch (e: FileAccess.SecurityEscape) {
            send(ex, 403, "application/json", """{"error":"forbidden"}""")
        }
    }

    /** server.properties の最低検証: 空行・# / ! コメント以外は key=value (key は英数字. _ -) */
    private fun isValidProperties(content: String): Boolean {
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue
            val i = t.indexOf('=')
            if (i <= 0) return false
            val key = t.substring(0, i).trim()
            if (key.isEmpty() || key.contains(' ')) return false
            for (ch in key) {
                if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' &&
                    ch != '.' && ch != '_' && ch != '-'
                ) {
                    return false
                }
            }
        }
        return true
    }

    companion object {
        private const val MAX_BODY = 8L * 1024 * 1024
    }
}
