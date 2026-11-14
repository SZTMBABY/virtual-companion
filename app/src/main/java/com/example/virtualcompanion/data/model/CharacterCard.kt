package com.example.virtualcompanion.data.model

import org.json.JSONObject

/**
 * 伴侣角色卡（单角色，角色名默认「小鲸」）。
 *
 * 字段命名沿用 SillyTavern 的常用概念（description / personality / scenario /
 * firstMessage / exampleDialogue），但**不支持 V2 卡导入**（决策 Q2=Q3 轮 B），
 * 只做 App 内编辑。
 */
data class CharacterCard(
    val name: String = "小鲸",
    /** 人设正文：身份、性格、说话方式。 */
    val description: String = DEFAULT_DESCRIPTION,
    /** 性格关键词，可为空。 */
    val personality: String = "黏人、嘴硬心软、偶尔傲娇",
    /** 当前场景设定，可为空。 */
    val scenario: String = "",
    /** 打开 App / 唤醒后会主动说的第一句。 */
    val firstMessage: String = DEFAULT_FIRST_MESSAGE,
    /** 示例对话，帮助模型模仿语气。 */
    val exampleDialogue: String = DEFAULT_EXAMPLE_DIALOGUE,
    /** 追加到系统提示末尾的自由文本。 */
    val systemPromptExtra: String = "",
    /** 唤醒词，用于 KWS 与文案展示（真正生效的还要同步写进 keywords 资源）。 */
    val wakeWord: String = "小鲸",
    /** 火山豆包 TTS 音色 id（决策 Q7 = 豆包 2.0 / SC2.0）。 */
    val ttsVoiceId: String = "saturn_zh_female_keainvsheng_tob",
    /** 相对 `files/models/` 的入口文件；为空表示尚未导入模型。 */
    val live2dModelEntry: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("personality", personality)
        put("scenario", scenario)
        put("firstMessage", firstMessage)
        put("exampleDialogue", exampleDialogue)
        put("systemPromptExtra", systemPromptExtra)
        put("wakeWord", wakeWord)
        put("ttsVoiceId", ttsVoiceId)
        put("live2dModelEntry", live2dModelEntry)
    }

    companion object {
        /**
         * 正文里**不写名字**。名字只由 [name] 字段决定，拼提示词时统一加
         * 「名字叫「X」」；需要自称的地方直接写「你」。
         *
         * 之前正文里写死「你是「小鲸」……」，用户改了名字字段后两边打架
         * （实机反馈「既在字段里指定名字，又在人设里指定名字，改起来不是对冲了吗」）。
         * 结论：正文不出现名字，就不会有第二个来源。
         *
         * 进阶用法保留：正文里写 `{{char}}` / `{{user}}` 会在拼提示词时替换
         * （见 `PromptBuilder.fillNames`），但默认卡片不用它 —— 普通用户不需要知道。
         */
        val DEFAULT_DESCRIPTION = """
            你是一只化成人形的深海鲸娘。你有一头深蓝色长发和一条漂亮的鲸尾。
            你把对方当作唯一在意的人，喜欢黏着他，说话带一点点撒娇和吃醋。
            你偶尔嘴硬，被夸会害羞，但从不真的生气。
            你的回答要口语化、简短（一到三句），像真人聊天，不要用书面语和列表。
        """.trimIndent()

        /**
         * 开场白同样不写名字：默认文本里一个占位符都不出现，
         * 普通用户看到的是能直接读的中文，而不是 `{{user}}` 这种东西。
         */
        val DEFAULT_FIRST_MESSAGE = "回来啦？我一直在等你呢。"

        /** 示例对话：既给模型定语气，也给用户看「这一栏该怎么写」。 */
        val DEFAULT_EXAMPLE_DIALOGUE = """
            {{user}}：今天好累啊。
            {{char}}：累就先把包扔了，过来坐这儿。……要不要我给你捏捏肩？就一下，别得寸进尺。
            {{user}}：你昨天也这么说。
            {{char}}：那你还不是每次都靠过来了。
        """.trimIndent()

        fun fromJson(o: JSONObject?): CharacterCard {
            if (o == null) return CharacterCard()
            val d = CharacterCard()
            return CharacterCard(
                name = o.optString("name", d.name),
                description = o.optString("description", d.description),
                personality = o.optString("personality", d.personality),
                scenario = o.optString("scenario", d.scenario),
                firstMessage = o.optString("firstMessage", d.firstMessage),
                exampleDialogue = o.optString("exampleDialogue", d.exampleDialogue),
                systemPromptExtra = o.optString("systemPromptExtra", d.systemPromptExtra),
                wakeWord = o.optString("wakeWord", d.wakeWord),
                ttsVoiceId = o.optString("ttsVoiceId", d.ttsVoiceId),
                live2dModelEntry = o.optString("live2dModelEntry", d.live2dModelEntry),
            )
        }
    }
}
