package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path

/**
 * GET {base}/api/file?path=<rel> — テキストファイルの内容 + ETag。
 * バイナリ(拡張子・NUL・非 UTF-8)は 415。保護対象は 403。
 */
class FileReadHandler(private val fa: FileAccess) {

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
            val bytes = Files.readAllBytes(real)
            if (!fa.isUtf8(bytes)) {
                send(ex, 415, "application/json", """{"error":"not utf-8"}""")
                return
            }
            ex.responseHeaders.add("ETag", "\"" + fa.etag(real) + "\"")
            send(ex, 200, "text/plain; charset=utf-8", String(bytes, Charsets.UTF_8))
        } catch (e: FileAccess.SecurityEscape) {
            send(ex, 403, "application/json", """{"error":"forbidden"}""")
        }
    }
}
