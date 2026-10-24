package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path

/** GET {base}/api/files?path=<rel> — ディレクトリ一覧 (name / type / size / mtime) */
class FileListHandler(private val fa: FileAccess) {

    private val gson = Gson()

    fun handle(ex: HttpExchange) {
        val rel = fa.normalizeRel(queryParam(ex, "path"))
        if (rel == null) {
            send(ex, 400, "application/json", """{"error":"bad path"}""")
            return
        }
        try {
            if (rel.isEmpty()) {
                send(ex, 200, "application/json; charset=utf-8", gson.toJson(listRoots()))
                return
            }
            val real = fa.resolve(rel)
            if (real == null) {
                send(ex, 404, "application/json", """{"error":"not found"}""")
                return
            }
            if (!Files.isDirectory(real)) {
                send(ex, 400, "application/json", """{"error":"not a directory"}""")
                return
            }
            send(ex, 200, "application/json; charset=utf-8", gson.toJson(listDir(real)))
        } catch (e: FileAccess.SecurityEscape) {
            send(ex, 403, "application/json", """{"error":"forbidden"}""")
        }
    }

    /** ルート: 許可ルートの集合 (ファイルはそのまま、ディレクトリは自身) */
    private fun listRoots(): JsonArray {
        val out = JsonArray()
        val seen = HashSet<String>()
        for (root in fa.fileRoots) {
            val p = fa.serverRoot.resolve(root)
            val real = try {
                p.toRealPath()
            } catch (_: Exception) {
                null
            } ?: continue
            val o = entry(real)
            if (seen.add(o.get("name").asString)) out.add(o)
        }
        return out
    }

    private fun listDir(dir: Path): JsonArray {
        val out = JsonArray()
        Files.list(dir).use { stream ->
            // エントリの自分のパスで出す(シンボリックリンクはリンク名)。
            // 実体が壊れていても一覧自体は壊さない。
            val entries = stream.toList().sortedWith(compareByDescending<Path> {
                Files.isDirectory(it)
            }.thenBy { it.fileName.toString().lowercase() })
            for (p in entries) {
                out.add(entry(p))
            }
        }
        return out
    }

    private fun entry(p: Path): JsonObject {
        val o = JsonObject()
        o.addProperty("name", p.fileName?.toString().orEmpty())
        val isDir = try {
            Files.isDirectory(p)
        } catch (_: Exception) {
            false
        }
        o.addProperty("type", if (isDir) "dir" else "file")
        o.addProperty("size", if (isDir) 0L else safeSize(p))
        o.addProperty("mtime", safeMtime(p))
        return o
    }

    private fun safeSize(p: Path): Long = try {
        Files.size(p)
    } catch (_: Exception) {
        0L
    }

    private fun safeMtime(p: Path): Long = try {
        Files.getLastModifiedTime(p).toMillis()
    } catch (_: Exception) {
        0L
    }
}
