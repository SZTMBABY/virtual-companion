package com.example.virtualcompanion.data

import com.example.virtualcompanion.data.model.CharacterCard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色卡导入/导出的回归测试。
 *
 * 这是本仓库第一组真正测试业务逻辑的用例（之前只有 `assertEquals(4, 2 + 2)`）。
 * 挑这里先补，是因为角色卡解析是全项目里**唯一**要吃外部世界提供的、
 * 不可控的、格式还分三个版本的数据的地方 —— 出错不会崩，只会让人设悄悄变样。
 */
class CharacterCardTest {

    // ------------------------------------------------------------ 导入

    @Test
    fun `v2 card maps six fields`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "阿蓝",
                "description": "喜欢在海边散步",
                "personality": "安静",
                "scenario": "夏天",
                "first_mes": "你来了。",
                "mes_example": "<START>\n{{user}}: 你好\n{{char}}: 嗯。"
              }
            }
        """.trimIndent()

        val r = CharacterCardImporter.parse(json)
        assertTrue(r.message, r.ok)
        assertEquals("V2", r.spec)
        val c = r.card!!
        assertEquals("阿蓝", c.name)
        assertEquals("喜欢在海边散步", c.description)
        assertEquals("安静", c.personality)
        assertEquals("夏天", c.scenario)
        assertEquals("你来了。", c.firstMessage)
        assertTrue(c.exampleDialogue.contains("你好"))
    }

    @Test
    fun `v1 flat card is also accepted`() {
        val r = CharacterCardImporter.parse(
            """{"name":"老王","description":"一个老头","personality":"暴躁"}""",
        )
        assertTrue(r.message, r.ok)
        assertEquals("V1", r.spec)
        assertEquals("老王", r.card!!.name)
    }

    @Test
    fun `null field does not become the word null`() {
        // org.json 的 optString 会把 JSON 的 null 读成字符串 "null"，
        // 那样人设里会平白多出四个字符，提示词也跟着脏。
        val r = CharacterCardImporter.parse(
            """{"spec":"chara_card_v2","data":{"name":"小雨","personality":null,"scenario":null}}""",
        )
        assertTrue(r.message, r.ok)
        assertEquals("", r.card!!.personality)
        assertEquals("", r.card!!.scenario)
    }

    @Test
    fun `card without name is rejected`() {
        val r = CharacterCardImporter.parse("""{"description":"没有名字的卡"}""")
        assertFalse(r.ok)
        assertTrue(r.message.contains("没有名字"))
    }

    @Test
    fun `broken json is rejected instead of throwing`() {
        val r = CharacterCardImporter.parse("""{"name": "被截断的""")
        assertFalse(r.ok)
        assertTrue(r.message.isNotBlank())
    }

    @Test
    fun `png card is rejected with an instruction`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val r = CharacterCardImporter.parseBytes(png)
        assertFalse(r.ok)
        assertTrue(r.message.contains("PNG"))
        // 不能只说「不支持」，要告诉用户下一步怎么办。
        assertTrue(r.message.contains("JSON"))
    }

    @Test
    fun `world book is reported as dropped`() {
        // 「没法用的地方直接丢掉」是用户拍定的，但丢掉必须说出来。
        val json = """
            {"spec":"chara_card_v2","data":{
              "name":"带世界书的卡",
              "character_book":{"entries":[{"keys":["a"],"content":"x"},{"keys":["b"],"content":"y"}]},
              "alternate_greetings":["早上好","晚上好"],
              "creator_notes":"作者留言"
            }}
        """.trimIndent()
        val r = CharacterCardImporter.parse(json)
        assertTrue(r.ok)
        val joined = r.ignored.joinToString()
        assertTrue(joined, joined.contains("世界书"))
        assertTrue(joined, joined.contains("2 条"))
        assertTrue(joined, joined.contains("备用开场白"))
        assertTrue(joined, joined.contains("作者备注"))
    }

    @Test
    fun `system prompt is downgraded not replaced`() {
        val json = """
            {"spec":"chara_card_v2","data":{
              "name":"x","system_prompt":"你要用第三人称说话"
            }}
        """.trimIndent()
        val r = CharacterCardImporter.parse(json)
        assertTrue(r.ok)
        // 内容留下来当补充要求……
        assertEquals("你要用第三人称说话", r.card!!.systemPromptExtra)
        // ……但必须告诉用户语义被改了，否则他以为整段提示词被换掉了。
        assertTrue(r.adjusted.joinToString().contains("追加"))
    }

    @Test
    fun `wake word falls back to default when extension is absent`() {
        // 外部卡没有唤醒词，不能导入成空 —— 空的唤不醒。
        val r = CharacterCardImporter.parse("""{"name":"外部角色"}""")
        assertTrue(r.ok)
        assertEquals(CharacterCard().wakeWord, r.card!!.wakeWord)
        assertEquals(CharacterCard().ttsVoiceId, r.card!!.ttsVoiceId)
    }

    // ------------------------------------------------------------ 导出

    @Test
    fun `export writes a v2 card that can be read back`() {
        val card = CharacterCard(
            name = "小鲸",
            description = "深海里的小鲸鱼",
            personality = "黏人",
            scenario = "深夜",
            firstMessage = "回来啦？",
            exampleDialogue = "{{user}}：在吗\n{{char}}：在。",
        )
        val out = CharacterCardExporter.toV2Json(card)
        assertEquals("chara_card_v2", JSONObject(out).getString("spec"))

        // 关键闭环：自己导出的卡必须能被自己导回来，且六项一致。
        val back = CharacterCardImporter.parse(out)
        assertTrue(back.message, back.ok)
        assertEquals("V2", back.spec)
        val got = back.card!!
        assertEquals(card.name, got.name)
        assertEquals(card.description, got.description)
        assertEquals(card.personality, got.personality)
        assertEquals(card.scenario, got.scenario)
        assertEquals(card.firstMessage, got.firstMessage)
        assertEquals(card.exampleDialogue, got.exampleDialogue)
    }

    @Test
    fun `export keeps our private fields in extensions`() {
        val card = CharacterCard(name = "小鲸", wakeWord = "阿鲸")
        val back = CharacterCardImporter.parse(CharacterCardExporter.toV2Json(card))
        assertEquals("阿鲸", back.card!!.wakeWord)
    }

    @Test
    fun `system prompt extra is appended to description`() {
        val card = CharacterCard(
            name = "x",
            description = "设定正文",
            systemPromptExtra = "说话短一点",
        )
        val data = JSONObject(CharacterCardExporter.toV2Json(card))
            .getJSONObject("data")
        val desc = data.getString("description")
        assertTrue(desc, desc.contains("设定正文"))
        assertTrue(desc, desc.contains("说话短一点"))
        // 不能写进 system_prompt：那是「整段替换」语义，会顶掉酒馆自己的框架。
        assertEquals("", data.getString("system_prompt"))
    }

    @Test
    fun `file name strips characters that break saf`() {
        val name = CharacterCardExporter.suggestedFileName(
            CharacterCard(name = """a/b:c*d?"e<f>g|h"""),
        )
        // 八个非法字符各变一个下划线；? 和 " 相邻所以出现连续两个下划线，这是对的。
        assertEquals("a_b_c_d__e_f_g_h.json", name)
        assertTrue(CharacterCardExporter.suggestedFileName(CharacterCard(name = "   ")).endsWith(".json"))
    }
}
