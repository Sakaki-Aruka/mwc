package online.aruka.mwc

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * ファイル管理の共有部品: パス検証 / 許可ルート解決 / 保護対象 / テキスト判定。
 *
 * すべてのファイル操作(一覧・閲覧・編集・アップロード・削除)は
 * [normalizeRel] + [resolve] を通すこと:
 *  - `\`・`:`・制御文字・先頭 `/`・`..` セグメントを拒否
 *  - 正規化後は許可ルート (file-manager.roots) 配下であること。サーバーディレクトリ自体は許可ルートではない
 *  - シンボリックリンク解決 (toRealPath) 後も許可ルート配下であること
 */
class FileAccess(private val plugin: MwcPlugin, val config: MwcConfig) {

    /**
     * サーバーディレクトリ (plugins/MWC の親の親。実パス)。
     * dataFolder は実行時に相対パス (plugins/MWC) の場合があるため、
     * 先に toRealPath() で絶対化してから親を辿る (相対パスは parent が null になり得る)。
     */
    val serverRoot: Path = plugin.dataFolder.toPath().toRealPath().parent!!.parent!!

    /** 設定の許可ルート (先頭 / を落とした相対パス) */
    val fileRoots: List<String> = config.fileRoots.map { it.trimStart('/') }.filter { it.isNotEmpty() }

    private val dataFolderReal: Path = plugin.dataFolder.toPath().toRealPath()

    /**
     * 自プラグインの jar (実パス)。ファイル名は利用者が変えることがあるので名前では決めない。
     * Paper 1.21 は plugins/ の jar を plugins/.paper-remapped/ に変換して読み込み、getFile() はその写しを返す。
     * 置き場所の jar は、plugins/ 直下の jar のうち plugin.yml の main がこのクラスのものとして起動時に探す。
     */
    private val ownJars: Set<Path> = findOwnJars()

    private fun findOwnJars(): Set<Path> {
        val out = HashSet<Path>()
        try {
            out.add(plugin.jarFile.toPath().toRealPath())
        } catch (_: Exception) {
        }
        try {
            Files.newDirectoryStream(dataFolderReal.parent, "*.jar").use { ds ->
                for (p in ds) if (declaresThisPlugin(p)) out.add(p.toRealPath())
            }
        } catch (e: Exception) {
            plugin.logger.warning("MWC: could not scan plugins/ for the own jar: ${e.message}")
        }
        return out
    }

    private fun declaresThisPlugin(jar: Path): Boolean = try {
        java.util.zip.ZipFile(jar.toFile()).use { z ->
            val e = z.getEntry("plugin.yml") ?: return false
            val yml = z.getInputStream(e).bufferedReader().readText()
            Regex("""(?m)^main:\s*['"]?""" + Regex.escape(MwcPlugin::class.java.name) + """['"]?\s*$""").containsMatchIn(yml)
        }
    } catch (_: Exception) {
        false
    }

    /** パスが許可ルート外に出た場合 (シンボリックリンク経由を含む) */
    class SecurityEscape(val requested: String) : RuntimeException(requested)

    /**
     * クエリパラメータ `path` ([queryParam] でデコード済み。ここで再デコードしない) を検証する。
     * 正規化した相対パス (先頭 / なし・`..` なし) を返す。不正なら null。
     * 空/未指定 → "" (ルート一覧)。
     */
    fun normalizeRel(raw: String?): String? {
        if (raw == null || raw.isBlank()) return ""
        if (raw.startsWith("/")) return null
        for (ch in raw) {
            if (ch == '\\' || ch == ':' || ch.code < 32 || ch.code == 127) return null
        }
        val parts = raw.split('/').filter { it.isNotEmpty() }
        for (p in parts) if (p == "..") return null
        return parts.joinToString("/")
    }

    /**
     * [rel] (normalizeRel 済み) を実パスに解決する。
     * @return 存在しなければ null。許可ルート外 ("" = サーバーディレクトリ自体を含む) は [SecurityEscape] を送出。
     */
    fun resolve(rel: String): Path? {
        if (rel.isEmpty()) throw SecurityEscape(rel)
        val target = serverRoot.resolve(rel)
        val real = try {
            target.toRealPath()
        } catch (_: Exception) {
            return null
        }
        if (!isUnderRoot(real)) throw SecurityEscape(rel)
        return real
    }

    /** [real] が許可ルートそのものかその配下か (まだ存在しないパスはリンク解決済みの親と名前で渡す) */
    fun isUnderRoot(real: Path): Boolean {
        for (root in fileRoots) {
            val relRoot = serverRoot.resolve(root).normalize()
            val realRoot = try {
                relRoot.toRealPath()
            } catch (_: Exception) {
                null
            }
            if (realRoot != null && (real == realRoot || real.startsWith(realRoot))) return true
            if (real.startsWith(relRoot)) return true
        }
        return false
    }

    /** 保護対象: 自プラグインの認証トークンファイル・自 jar・ログキャプチャファイル */
    fun isProtected(real: Path): Boolean {
        if (real == dataFolderReal.resolve("token.key")) return true
        if (real in ownJars) return true
        val cap = config.captureFile
        if (cap.isNotEmpty() && real == dataFolderReal.resolve(cap)) return true
        return false
    }

    /** テキストファイル: 拡張子許可リスト + 通常ファイル + 先頭 8KB に NUL なし */
    fun isTextFile(p: Path): Boolean {
        if (!Files.isRegularFile(p)) return false
        val name = p.fileName?.toString().orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        if (name.substring(dot).lowercase() !in config.textExtensions) return false
        return !hasNulPrefix(p)
    }

    private fun hasNulPrefix(p: Path): Boolean {
        Files.newInputStream(p).use { ins ->
            return ins.readNBytes(8192).contains(0)
        }
    }

    fun isUtf8(bytes: ByteArray): Boolean {
        val dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            dec.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    /** ETag = mtime-size (競合判定用。変更されると変わる。ヘッダ送出時は引用符で包む) */
    fun etag(p: Path): String =
        "${Files.getLastModifiedTime(p).toMillis()}-${Files.size(p)}"
}

/** クエリパラメータを URL デコードして読む */
fun queryParam(ex: HttpExchange, name: String): String? {
    val raw = ex.requestURI.rawQuery ?: return null
    for (part in raw.split("&")) {
        val i = part.indexOf('=')
        if (i < 0) continue
        val k = try {
            URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            continue
        }
        if (k == name) {
            return try {
                URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
    return null
}
