package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * POST {base}/api/files/upload?path=<rel>[&overwrite=true]
 *
 * リクエストボディをそのままファイル内容とする (multipart なし、任意の種別・jar 可)。
 *  - ボディはストリームで一時ファイルに書き出し、サイズ上限 (max-upload-size) 超過で 413 + 一時ファイル削除
 *  - 既存ファイルの上書きは overwrite=true のみ (未指定なら 409)、上書き前に .bak 作成
 *  - パス検証は M5 と共通 (normalizeRel + 親の resolve + isProtected)。
 *    保存先 (親 + 名前) も許可ルート配下であること (サーバーディレクトリ直下は server.properties など
 *    ルートとして指定されたファイルだけ)。ファイル名は先頭ドット連続・"." を追加で拒否
 *  - 途中で切断・失敗したら一時ファイルを削除する
 */
class FileUploadHandler(
    private val fa: FileAccess,
    private val config: MwcConfig,
    private val auth: Auth
) {

    private class TooLarge : RuntimeException()

    fun handle(ex: HttpExchange) {
        val rel = fa.normalizeRel(queryParam(ex, "path"))
        if (rel == null) {
            send(ex, 400, "application/json", """{"error":"bad path"}""")
            return
        }
        val idx = rel.lastIndexOf('/')
        val parentRel = if (idx < 0) "" else rel.substring(0, idx)
        val name = if (idx < 0) rel else rel.substring(idx + 1)
        if (name.isEmpty() || name == "." || name.startsWith("..")) {
            send(ex, 400, "application/json", """{"error":"bad file name"}""")
            return
        }
        try {
            val parentReal = if (parentRel.isEmpty()) fa.serverRoot else fa.resolve(parentRel)
            if (parentReal == null || !Files.isDirectory(parentReal)) {
                send(ex, 404, "application/json", """{"error":"not found"}""")
                return
            }
            val target = parentReal.resolve(name)
            if (!fa.isUnderRoot(target)) throw FileAccess.SecurityEscape(rel)
            if (Files.isSymbolicLink(target) || fa.isProtected(target)) {
                send(ex, 403, "application/json", """{"error":"protected"}""")
                return
            }
            if (Files.isDirectory(target)) {
                send(ex, 409, "application/json", """{"error":"a directory exists at this path"}""")
                return
            }
            val overwrite = queryParam(ex, "overwrite") == "true"
            if (Files.exists(target) && !overwrite) {
                send(ex, 409, "application/json", """{"error":"exists"}""")
                return
            }
            val size = writeStream(ex, parentReal, target, overwrite)
            auth.audit(ex, "upload", rel, size)
            send(ex, 200, "application/json", """{"ok":true,"size":$size}""")
        } catch (e: FileAccess.SecurityEscape) {
            send(ex, 403, "application/json", """{"error":"forbidden"}""")
        } catch (e: TooLarge) {
            send(ex, 413, "application/json", """{"error":"too large"}""")
        }
        // 送信途中の切断などの IOException は MwcHttp.handleApi が扱う (一時ファイルは writeStream が消している)
    }

    /** ボディを一時ファイルにストリーム書き込み → rename。超過は [TooLarge]。失敗時は一時ファイルを必ず削除する。 */
    private fun writeStream(ex: HttpExchange, dir: Path, target: Path, overwrite: Boolean): Long {
        val tmp = Files.createTempFile(dir, ".mwc-up-", ".tmp")
        var size = 0L
        try {
            ex.requestBody.use { ins ->
                Files.newOutputStream(tmp).use { outs ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > config.maxUploadSize) throw TooLarge()
                        outs.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Throwable) {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: Exception) {
            }
            throw e
        }
        try {
            if (overwrite && Files.exists(target)) {
                Files.copy(target, target.resolveSibling(target.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: Exception) {
            }
        }
        return size
    }
}
