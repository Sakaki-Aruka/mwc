package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files

/**
 * DELETE {base}/api/files?path=<rel>&confirm=true
 *
 * 削除は不可逆のため API 側でも confirm=true を要求 (なしは 400)。
 *  - ファイル: 削除 + 兄弟の <name>.bak も一緒に削除
 *  - ディレクトリ: 既定では空ディレクトリのみ (recursive-delete で再帰を有効化)
 *  - 許可ルートそのもの・保護対象は削除不可
 */
class FileDeleteHandler(
    private val fa: FileAccess,
    private val config: MwcConfig,
    private val auth: Auth
) {

    fun handle(ex: HttpExchange) {
        if (queryParam(ex, "confirm") != "true") {
            send(ex, 400, "application/json", """{"error":"confirm=true required"}""")
            return
        }
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
            if (fa.isProtected(real)) {
                send(ex, 403, "application/json", """{"error":"protected"}""")
                return
            }
            if (isRootItself(real)) {
                send(ex, 400, "application/json", """{"error":"cannot delete root"}""")
                return
            }
            val size = if (Files.isDirectory(real)) 0L else Files.size(real)
            if (Files.isDirectory(real)) {
                val empty = Files.newDirectoryStream(real).use { it.iterator().hasNext().not() }
                when {
                    empty -> Files.delete(real)
                    config.recursiveDelete -> deleteRecursively(real)
                    else -> {
                        send(ex, 400, "application/json", """{"error":"directory not empty"}""")
                        return
                    }
                }
            } else {
                Files.delete(real)
                // 編集・上書きで作られた <name>.bak も一緒に片付ける
                Files.deleteIfExists(real.resolveSibling(real.fileName.toString() + ".bak"))
            }
            auth.audit("delete", rel, size)
            send(ex, 200, "application/json", """{"ok":true}""")
        } catch (e: FileAccess.SecurityEscape) {
            send(ex, 403, "application/json", """{"error":"forbidden"}""")
        } catch (_: Exception) {
            send(ex, 500, "application/json", """{"error":"internal error"}""")
        }
    }

    /** 許可ルートそのもの (server.properties 等の単一ファイルも) は削除不可 */
    private fun isRootItself(real: java.nio.file.Path): Boolean {
        for (root in fa.fileRoots) {
            val rr = fa.serverRoot.resolve(root)
            val rReal = try {
                rr.toRealPath()
            } catch (_: Exception) {
                null
            }
            if (real == rr || (rReal != null && real == rReal)) return true
        }
        return false
    }

    private fun deleteRecursively(p: java.nio.file.Path) {
        Files.newDirectoryStream(p).use { ds ->
            for (child in ds) {
                if (Files.isDirectory(child)) deleteRecursively(child)
                Files.deleteIfExists(child)
            }
        }
        Files.deleteIfExists(p)
    }
}
