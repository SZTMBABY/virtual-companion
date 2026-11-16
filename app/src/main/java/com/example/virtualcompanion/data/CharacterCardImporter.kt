package com.example.virtualcompanion.data

import com.example.virtualcompanion.data.model.CharacterCard
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 导入社区角色卡（SillyTavern 的 Character Card V1 / V2 / V3 的 JSON 文件）。
 *
 * 设计前提是用户拍定的一句话：「可以兼容导入，没法用的地方就直接丢掉。」
 * 所以这里**只取六项人设字段**，世界书、备用开场白、内嵌资产等等一律不保存。
 *
 * 代价必须记住：**不保留原卡**。带世界书的卡导入之后再导出，世界书是回不来的。
 * 因此丢弃的东西不能悄悄扔 —— 全部塞进 [Report.ignored]，由界面当面念给用户听。
 *
 * 字段映射（社区的键名 → 我们的字段）：
 * ```
 * name            → name
 * description     → description
 * personality     → personality
 * scenario        → scenario
 * first_mes       → firstMessage
 * mes_example     → exampleDialogue
 * system_prompt   → systemPromptExtra（**只追加，不替换**）
 * extensions.virtual_companion.{wakeWord,ttsVoiceId,live2dModelEntry} → 对应字段
 * ```
 *
 * `system_prompt` 为什么降级成追加：社区的语义是「整段替换掉前端的系统提示词」。
 * 真按这个来，它会顶掉我们要求模型只输出一行 `{"say","emotion","motion"}` 的协议，
 * App 当场解析失败、一句都说不出来。所以它的内容只作为「补充要求」追加，
 * 输出协议永远由我们最后追加，不可被外部卡替换。
 */
object CharacterCardImporter {

    /** 一张卡不该有 4MB 这么大；超过基本是选错文件了。 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * 导入结果。
     *
     * @param ok 解析成功且拿到了可用的名字。
     * @param card 解析出的角色卡；[ok] 为 false 时是 null。
     * @param message 失败原因，成功时为空。
     * @param spec 识别出的规范版本（"V1" / "V2" / "V3"），用于界面上标一句来源。
     * @param imported 真正吃进去的部分，逐条显示。
     * @param adjusted 吃进去了但语义被改过的部分（目前只有 system_prompt）。
     * @param ignored 丢弃了的部分。**这一项是良心**，不显示就等于骗人。
     */
    data class Report(
        val ok: Boolean,
        val card: CharacterCard? = null,
        val message: String = "",
        val spec: String = "",
        val imported: List<String> = emptyList(),
        val adjusted: List<String> = emptyList(),
        val ignored: List<String> = emptyList(),
    ) {
        /**
         * 拼成给人看的中文清单。
         *
         * **顺序是坏消息在前**，不是「用上了 → 改过 → 丢了」。第一版按正序写，
         * 真机截图一看：弹窗要滑才看得见底部，「没保存」整段落在折叠线外，
         * 用户读完「用上了」就点了「知道了」。丢掉的东西只有一次机会被看见。
         *
         * 放在这里而不是 Activity 里，是为了能被单元测试钉住顺序
         * （见 `CharacterCardTest.describePutsLossesFirst`）。
         */
        fun describe(): String = buildString {
            if (ignored.isNotEmpty()) {
                append("这些东西没保存，再导出也没有了：\n")
                ignored.forEach { append("· ").append(it).append('\n') }
                append('\n')
            }
            append("识别为 ").append(spec.ifBlank { "未知版本" }).append(" 卡。\n")
            if (imported.isNotEmpty()) {
                append("\n用上了：\n")
                imported.forEach { append("· ").append(it).append('\n') }
            }
            if (adjusted.isNotEmpty()) {
                append("\n改过用法：\n")
                adjusted.forEach { append("· ").append(it).append('\n') }
            }
        }
    }

    /**
     * 从 SAF 打开的输入流读一张卡。
     *
     * 读取与解析放在同一层，Activity 那边只负责拿到流，
     * 这样「文件是什么格式」的判断只有一处。
     */
    fun importFrom(stream: InputStream): Report = runCatching {
        parseBytes(readCapped(stream))
    }.getOrElse { e ->
        Report(false, message = "读不了这个文件：${e.message ?: "未知错误"}")
    }

    /** 先看文件头判断载体，再当文本解析。 */
    fun parseBytes(bytes: ByteArray): Report {
        if (isPng(bytes)) {
            return Report(
                false,
                message = "这是 PNG 图片卡，当前版本只支持 .json。" +
                    "可以在酒馆里把角色「导出为 JSON」再导进来。",
            )
        }
        if (isZip(bytes)) {
            return Report(
                false,
                message = "这是 .charx 压缩包，当前版本只支持 .json。" +
                    "可以在酒馆里把角色「导出为 JSON」再导进来。",
            )
        }
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return Report(false, message = "文件是空的")
        return parse(text)
    }

    /** 解析卡 JSON 文本。 */
    fun parse(raw: String): Report {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Report(false, message = "这不是角色卡 JSON，或者内容被截断了")
        }

        // V2/V3 把内容放在 data 里；V1 是扁平的，直接把根当 data 用。
        val data = root.optJSONObject("data") ?: root
        val hasData = root.optJSONObject("data") != null
        val specRaw = text(root, "spec").lowercase()
        val spec = when {
            specRaw.contains("v3") -> "V3"
            specRaw.contains("v2") -> "V2"
            hasData -> "V2"
            else -> "V1"
        }

        val name = text(data, "name")
        if (name.isBlank()) {
            return Report(
                false,
                spec = spec,
                message = "这张卡没有名字，读不出她是谁。可能是选错了文件。",
            )
        }

        val description = text(data, "description")
        val personality = text(data, "personality")
        val scenario = text(data, "scenario")
        val firstMessage = text(data, "first_mes")
        val exampleDialogue = text(data, "mes_example")
        val systemPrompt = text(data, "system_prompt")

        // 我们自己导出的卡会把这四项放在 extensions 里，导回来时还原。
        val ext = data.optJSONObject("extensions")?.optJSONObject("virtual_companion")
        val wakeWord = ext?.let { text(it, "wakeWord") } ?: ""
        val ttsVoiceId = ext?.let { text(it, "ttsVoiceId") } ?: ""
        val modelEntry = ext?.let { text(it, "live2dModelEntry") } ?: ""

        val fallback = CharacterCard()
        val card = CharacterCard(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
            exampleDialogue = exampleDialogue,
            systemPromptExtra = systemPrompt,
            // 唤醒词留空等于叫不醒，音色留空等于没声音，都不能让外部卡把默认值冲掉。
            wakeWord = wakeWord.ifBlank { fallback.wakeWord },
            ttsVoiceId = ttsVoiceId.ifBlank { fallback.ttsVoiceId },
            live2dModelEntry = modelEntry,
        )

        val imported = buildList {
            add("名字「$name」")
            if (description.isNotBlank()) add("设定")
            if (personality.isNotBlank()) add("性格")
            if (scenario.isNotBlank()) add("场景")
            if (firstMessage.isNotBlank()) add("开场白")
            if (exampleDialogue.isNotBlank()) add("示例对话")
            if (systemPrompt.isNotBlank()) add("补充要求")
            if (wakeWord.isNotBlank()) add("唤醒词「$wakeWord」")
            if (ttsVoiceId.isNotBlank()) add("音色 ID")
            if (modelEntry.isNotBlank()) add("Live2D 模型绑定")
        }

        val adjusted = buildList {
            if (systemPrompt.isNotBlank()) {
                add("system_prompt 只作为「补充要求」追加，不替换本 App 的输出格式要求")
            }
            // V2 要求 mes_example 用 <START> 分段；我们只整段塞进提示词，
            // 模型能读到，但分段语义丢了。这一点也要说。
            if (exampleDialogue.contains("<START>")) {
                add("示例对话里的 <START> 分段标记没有去掉，会原样进提示词")
            }
        }

        val ignored = buildList {
            val book = data.optJSONObject("character_book") ?: data.optJSONObject("lorebook")
            if (book != null) {
                val n = book.optJSONArray("entries")?.length() ?: 0
                add(if (n > 0) "世界书（$n 条词条）" else "世界书")
            }
            countOf(data, "alternate_greetings").takeIf { it > 0 }
                ?.let { add("备用开场白（$it 条）") }
            countOf(data, "group_only_greetings").takeIf { it > 0 }
                ?.let { add("群聊开场白（$it 条）") }
            countOf(data, "assets").takeIf { it > 0 }
                ?.let { add("内嵌资产（$it 个）") }
            if (text(data, "creator_notes").isNotBlank()) add("作者备注")
            if (text(data, "post_history_instructions").isNotBlank()) {
                add("post_history_instructions（后置指令）")
            }
        }

        return Report(
            ok = true,
            card = card,
            spec = spec,
            imported = imported,
            adjusted = adjusted,
            ignored = ignored,
        )
    }

    // ------------------------------------------------------------------ 内部

    private fun readCapped(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(chunk)
            if (n <= 0) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_BYTES) {
                throw IllegalStateException("超过 4MB，不像是一张角色卡")
            }
        }
        return out.toByteArray()
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun isZip(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
            b[2] == 3.toByte() && b[3] == 4.toByte()

    /**
     * 取字符串字段。
     *
     * 不能直接用 `optString`：JSON 里的 `null` 在 Android 的 org.json 里会变成
     * 字面量 "null"，一张 `"personality": null` 的卡会让人设里多出两个字「null」。
     */
    private fun text(o: JSONObject, key: String): String {
        val v = o.opt(key) ?: return ""
        if (v === JSONObject.NULL) return ""
        val s = if (v is String) v else v.toString()
        return s.trim()
    }

    private fun countOf(o: JSONObject, key: String): Int = when (val v = o.opt(key)) {
        is JSONArray -> v.length()
        is JSONObject -> 1
        else -> 0
    }
}
