package com.example.virtualcompanion.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 把 SAF 选中的目录树导入 `files/models/<模型名>/`。
 *
 * 用「选择文件夹」（OpenDocumentTree）而不是「选择多个文件」，因为导出包里通常有
 * 贴图子目录（本项目样例是 `大肥鱼.8192/texture_00.png`），多选文件拿不到子目录。
 *
 * 遍历会把所有文件**拍平**到目标目录：Cubism 的 model3.json 只按文件名引用贴图，
 * 拍平不破坏引用；重名时后写覆盖并打日志。
 */
class ModelImporter(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val modelName: String = "",
        val fileCount: Int = 0,
        val message: String = "",
    )

    /**
     * @param treeUri 用户用 OpenDocumentTree 选中的目录
     * @param modelName 目标模型名，留空则用目录名
     */
    fun import(treeUri: Uri, modelName: String? = null): Result {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Result(false, message = "无法读取所选目录")

        val suggestedName = modelName?.takeIf { it.isNotBlank() }
            ?: root.name?.takeIf { it.isNotBlank() }
            ?: "model"

        val files = ArrayList<Pair<String, File>>()
        val tmpDir = File(context.cacheDir, "model_import_${System.currentTimeMillis()}")
        if (!tmpDir.mkdirs()) return Result(false, message = "无法创建临时目录")

        try {
            collect(root, tmpDir, files, 0)
        } catch (e: Exception) {
            Log.e(TAG, "collect failed", e)
            tmpDir.deleteRecursively()
            return Result(false, message = "读取文件失败：${e.message}")
        }

        if (files.isEmpty()) {
            tmpDir.deleteRecursively()
            return Result(false, message = "目录是空的，或没有可识别的 Live2D 文件")
        }
        if (files.none { it.first.endsWith(".model3.json") }) {
            tmpDir.deleteRecursively()
            return Result(false, message = "没找到 .model3.json，请选择模型文件所在的那个文件夹")
        }

        val model = ModelRepository(context).importFiles(suggestedName, files)
        tmpDir.deleteRecursively()
        return if (model == null) {
            Result(false, message = "写入失败，请检查存储空间")
        } else {
            Result(true, model.name, files.size, "已导入 ${files.size} 个文件")
        }
    }

    private fun collect(
        dir: DocumentFile,
        tmpDir: File,
        out: MutableList<Pair<String, File>>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "skip deep dir ${dir.name}")
            return
        }
        dir.listFiles().forEach { child ->
            if (child.isDirectory) {
                // 记录子目录名，稍后拍平时保留原始文件名即可
                collect(child, tmpDir, out, depth + 1)
            } else {
                val name = child.name ?: return@forEach
                if (!ModelRepository.isSupported(name)) {
                    Log.d(TAG, "skip unsupported $name")
                    return@forEach
                }
                // 目标文件名用原始名（拍平），临时副本也用原始名
                val dst = File(tmpDir, name)
                runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }.onSuccess {
                    if (dst.exists() && dst.length() > 0) out.add(name to dst)
                }.onFailure {
                    Log.e(TAG, "read $name failed: $it")
                }
            }
        }
    }

    /** 展示给用户看的候选目录名（去掉 DocumentsContract 的 encoded path 噪音）。 */
    fun displayName(treeUri: Uri): String {
        val docId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return "model"
        return docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "model" }
    }

    companion object {
        private const val TAG = "ModelImporter"
        private const val MAX_DEPTH = 6
    }
}
