package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files

/**
 * DELETE {base}/api/files?path=<rel>&confirm=true
 *
 * 削除は不可逆のため API 側でも confirm=true を要求 (なしは 400)。
 *  - ファイル: 指定したものだけを削除する (.bak は残す。消すなら別に指定する)
 *  - シンボリックリンク (リンク先が許可ルート内のもの): リンク自体を削除し、リンク先は残す
 *  - ディレクトリ: 既定では空ディレクトリのみ (recursive-delete で再帰を有効化。再帰でもリンクはたどらない)
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
            val link = fa.serverRoot.resolve(rel)
            if (Files.isSymbolicLink(link)) {
                Files.delete(link)
                auth.audit(ex, "delete", "$rel (symlink)", 0L)
                send(ex, 200, "application/json", """{"ok":true}""")
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
            }
            auth.audit(ex, "delete", rel, size)
            send(ex, 200, "application/json", """{"ok":true}""")
        } catch (e: FileAccess.SecurityEscape) {
            send(ex, 403, "application/json", """{"error":"forbidden"}""")
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

    /** 再帰削除。シンボリックリンクはたどらずリンク自体を消す (リンク先がルート外でも中身は消さない) */
    private fun deleteRecursively(p: java.nio.file.Path) {
        Files.newDirectoryStream(p).use { ds ->
            for (child in ds) {
                if (Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) deleteRecursively(child)
                Files.deleteIfExists(child)
            }
        }
        Files.deleteIfExists(p)
    }
}
