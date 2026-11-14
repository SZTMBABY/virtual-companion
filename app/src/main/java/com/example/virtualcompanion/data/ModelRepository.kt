package com.example.virtualcompanion.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 一个已导入的 Live2D 模型。
 *
 * @param dir  模型目录的绝对路径，必须以 `/` 结尾，供 WebView 以 file:// 读取。
 * @param entry `.model3.json` 的文件名（相对 [dir]）。
 */
data class Live2DModel(
    val name: String,
    val dir: String,
    val entry: String,
) {
    val entryPath: String get() = dir + entry
}

/**
 * 管理 `files/models/` 下的用户模型（决策 D1：模型不进 APK）。
 *
 * 目录结构约定：
 * ```
 * files/models/
 *   └── <模型名>/
 *         ├── xxx.model3.json
 *         ├── xxx.moc3
 *         ├── texture_00.png
 *         ├── physics3.json
 *         └── cdi3.json
 * ```
 * 导入时把用户选中的多个文件递归平铺进 `<模型名>/`，贴图子目录也拍平——
 * moc3/Cubism 对贴图只按文件名引用，拍平不会破坏引用（重名时后写覆盖并记日志）。
 */
class ModelRepository(private val context: Context) {

    private val root: File get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /** 扫描所有已导入模型。按名字排序，保证 UI 稳定。 */
    fun listModels(): List<Live2DModel> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedBy { it.name }.mapNotNull { d ->
            val entries = d.listFiles { f -> f.isFile && f.name.endsWith(".model3.json") }
                ?: return@mapNotNull null
            if (entries.isEmpty()) return@mapNotNull null
            // 优先 model.model3.json，否则取第一个（名字排序，结果确定）
            val entry = entries.firstOrNull { it.name == "model.model3.json" }
                ?: entries.sortedBy { it.name }.first()
            Live2DModel(name = d.name, dir = d.absolutePath.trimEnd('/') + "/", entry = entry.name)
        }
    }

    fun defaultModel(): Live2DModel? = listModels().firstOrNull()

    fun hasAnyModel(): Boolean = listModels().isNotEmpty()

    /** 删除一个模型目录。 */
    fun deleteModel(name: String): Boolean {
        val dir = File(root, name)
        return dir.exists() && dir.deleteRecursively()
    }

    /**
     * 从 SAF 选中的文件列表导入。目录树（DocumentFile）展开由调用方完成，
     * 这里只接收「文件名 → 本地临时副本」的映射。
     *
     * @return 新建的模型，失败返回 null
     */
    fun importFiles(modelName: String, files: List<Pair<String, File>>): Live2DModel? {
        if (files.isEmpty()) return null
        val target = File(root, sanitize(modelName))
        if (target.exists()) target.deleteRecursively()
        if (!target.mkdirs()) {
            Log.e(TAG, "mkdirs failed: $target")
            return null
        }
        var hasModel3 = false
        files.forEach { (name, src) ->
            val flatName = src.name
            if (flatName.endsWith(".model3.json")) hasModel3 = true
            val dst = File(target, flatName)
            runCatching { src.copyTo(dst, overwrite = true) }
                .onFailure { Log.e(TAG, "copy $flatName failed: $it") }
        }
        if (!hasModel3) {
            Log.e(TAG, "import rejected: no .model3.json in $modelName")
            target.deleteRecursively()
            return null
        }
        return listModels().firstOrNull { it.name == target.name }
    }

    /**
     * 直接从一个目录导入（用于调试：adb push 到 /sdcard 后由 App 复制进来）。
     */
    fun importFromDirectory(modelName: String, source: File): Live2DModel? {
        if (!source.isDirectory) return null
        val collected = source.walkTopDown()
            .filter { it.isFile }
            .map { it.name to it }
            .toList()
        return importFiles(modelName, collected)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "model" }

    companion object {
        private const val TAG = "ModelRepository"
        const val MODELS_DIR = "models"

        /** 允许导入的扩展名——挡住误选的 mp4 之类大文件。 */
        val ALLOWED_EXT = setOf(
            "model3.json", "moc3", "png", "jpg", "jpeg", "webp",
            "physics3.json", "cdi3.json", "exp3.json", "motion3.json", "pose3.json",
            "vtube.json", "json",
        )

        fun isSupported(filename: String): Boolean {
            val lower = filename.lowercase()
            return ALLOWED_EXT.any { lower.endsWith(".$it") } ||
                lower.endsWith(".model3.json")
        }
    }
}
