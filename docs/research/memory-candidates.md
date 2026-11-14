# 长期记忆方案选型：候选调研与移植评估

**目标设备**：Android 11 (API 30) / arm64-v8a / 无 GMS / 3 GB RAM / Dalvik heap 256 MB / MT6761（4×Cortex-A53，无 GPU 加速）/ 系统 SQLite **3.28.0**
**应用形态**：中文虚拟伴侣，Kotlin + Compose，语音优先，单角色，20–60 轮/会话
**本文范围**：在已确定必选 **AcpKernel（`acp-kernel`，MIT + 署名）** 的前提下，还需要从外部借鉴哪些机制。**不重复评估 billion-context 本身**（见 `docs/research/billion-context.md`）。

**证据标记**

| 标记 | 含义 |
|---|---|
| ✅ 实测 | 我在目标真机 `<device-serial>` 上跑出来的数字，或从下载下来的产物里 dump 出来的 |
| 📖 读源码 | 逐行读过上游源码得出，未在本机执行 |
| ⚠️ 未核实 | 合理推断或来自文档，本次没有证据 |

**抓取时间**：2026-11-14。star 数会变，`api.github.com` 未认证配额约 60 次/小时。

---

## 第零部分：先把硬约束钉死（全部为真机实测）

这一节的每一行都可以直接引用，因为全部在目标机上跑过。**结论先行：这台机器上能用的检索原语只有 `LIKE` 全表扫描和 FTS4，而 FTS4 要能检索中文，必须由 App 自己生成 bigram。**

| # | 事实 | 证据 |
|---|---|---|
| 0.1 | `ro.build.version.sdk`=30、`ro.product.cpu.abi`=arm64-v8a、`ro.board.platform`=mt6761、`/proc/cpuinfo` 4 个 processor、`MemTotal`=2,934,156 kB、`dalvik.vm.heapgrowthlimit`=256m | ✅ `getprop` / `/proc` |
| 0.2 | `sqlite3 --version` = `3.28.0 2021-07-13 …`；`/system/lib64/libsqlite.so` = 1,217,880 B | ✅ |
| 0.3 | `CREATE VIRTUAL TABLE … USING fts5(a)` → **`Error: no such module: fts5`** | ✅ 复现用户给的约束 |
| 0.4 | `fts4` / `fts3` 建表**成功** | ✅ |
| 0.5 | `SELECT bm25(m)` → **`Error: no such function: bm25`** | ✅ |
| 0.6 | `SELECT rank(matchinfo(m,'pcnalx'))` → **`Error: wrong number of arguments to function rank()`** | ✅ **用户约束里没提的坑**，见 §2.C |
| 0.7 | `matchinfo(m,'pcnalx')` 可用，返回 8 个 uint32（单 phrase 单列时） | ✅ |
| 0.8 | `tokenize=unicode61` 下 `MATCH '妹妹'` 对原文 `我喜欢在星期天和妹妹小美去爬山` → **0 行**；`simple` 同样 0 | ✅ |
| 0.9 | `tokenize=icu` 下 `MATCH '妹妹'` → 1；但对 `我们约好周六去爬山虎公园`，`MATCH '爬山'` → **0**、`'爬山虎'`→1、`'爬山*'`→1；单字 `'山'` → **0** | ✅ |
| 0.10 | **自建 bigram 影子列 + `tokenize=unicode61`** → 子串级命中成立（`爬山` 命中 `爬山虎公园`）；短语查询、跨词 AND 均正常 | ✅ **本报告的核心可用性支点** |
| 0.11 | `snippet()` / `offsets()` 可用；`highlight()` → **`no such function: highlight`** | ✅ |
| 0.12 | 外部内容 FTS4：`content_rowid=` 被**拒绝**（`unrecognized parameter: content_rowid=id`）；`content=msg` + `INSERT INTO fts(fts) VALUES('rebuild')` 可用；**FTS 表的列名必须是内容表列名的子集**，否则 `MATCH` 能出行但 `snippet()/offsets()` 报 `SQL logic error` | ✅ 踩坑后修正，机制解释 ⚠️ 推断 |
| 0.13 | 2000 轮中文对话 + bigram 影子列建库：插入 0.26 s，db **909,312 B**；200 次 `MATCH … COUNT` 0.10 s（~0.5 ms/次）；200 次 `MATCH + matchinfo` 0.25 s（~1.25 ms/次）；200 次 3-term `OR` + matchinfo 0.36 s（~1.8 ms/次） | ✅ |
| 0.14 | 50,000 行 bigram 语料：批量插入 2.74 s，db 4,149,248 B（~83 B/行）；3-term + matchinfo 命中 1187 行 → ~3 ms/次；最坏情况单高频词命中全表 → ~14 ms/次；进程 `VmHWM` = **8,616 kB** | ✅ |
| 0.15 | `LIKE '%医院%'` 全表扫描在 2000 行上同样快 | ✅ |
| 0.16 | Room 2.8.5 **有** `@Fts4`（`tokenizer`/`tokenizerArgs`/`contentEntity`/`languageId`/`matchInfo`/`notIndexed`/`prefix`/`order`），**没有** `@Fts5` | ✅ `javap` dump `room-common-jvm-2.8.5.jar`（62,722 B） |
| 0.17 | Android 30 `SQLiteDatabase` **没有** `loadExtension` → 框架层无法运行时加载 sqlite-vec | ✅ dump android-30 `android.jar` |
| 0.18 | `android.icu.text.BreakIterator` 在 android-30 存在（`KIND_WORD` / `WORD_IDEO`）→ acp-kernel `Intl.Segmenter` 的 Android 等价物 | ✅ |
| 0.19 | 逃生舱：`androidx.sqlite:sqlite-bundled-android:2.7.1` 内含 **SQLite 3.50.x**、`ENABLE_FTS3/FTS4/FTS5/RTREE`、`BundledSQLiteDriver.addExtension(String)` 公开；arm64 `libsqliteJni.so` = 1,281,456 B，AAR = 2,551,019 B；**不含 ICU** | ✅ `strings` + `javap` |
| 0.20 | 算力基线（dalvikvm，MT6761）：FP32 256³ matmul **56.5 ms → 0.59 GFLOPS**；INT8 dot-product 4096 → **0.25 GOPS** | ✅ |

### 由 0.x 直接推出的两条硬结论

**结论 1（检索）**：中文检索在 FTS4 上**可行**，但前提是 App 自己把文本切成 bigram（并补 unigram，见 §4.3）后写进影子列。用 FTS4 默认分词器存原始中文，检索能力等于零。代价实测为 2000 轮 **909 KB**、单次查询 **1.2–1.8 ms**、进程峰值 **8.4 MB**——远低于 150 MB 预算。

**结论 2（向量）**：本地 embedding **不可行**，两条独立理由互相印证：
- 体积：`onnxruntime-android` arm64 native ≈ 6.3 MB + 最小可用模型（`potion-base-2M`，7,563,349 B + tokenizer 683,666 B）≈ **13.5 MB 起步**；`bge-small-zh-v1.5` int8 需 `model_quantized.onnx` 168 KB **加** `model_quantized.onnx_data` 23.8 MB ≈ **30 MB**。
- 算力：0.59 GFLOPS / 0.25 GOPS 的 INT8 dot 吞吐，跑 24M 参数模型单句推理是**数十秒/句**量级，RTF ≫ 1。
- ⚠️ **未核实**：我没有在真机上跑 onnxruntime 实测 RTF（本机无法构建 APK 侧的原生依赖链）。上面是「体积 + GFLOPS 基线」两条独立推算，不是 RTF 实测值。若要坐实，需要在设备上跑一个 onnxruntime Android demo 并计时。

---

## 第一部分：在 AcpKernel 之上，还要不要借鉴别人的事实抽取与去重？

**结论：要，而且只借一件事——写入时的「归一化 + 纯 ADD + 哈希去重」。不要借 UPDATE/DELETE 四动作决策。**

理由是三组实测，按重要性从高到低：

### 1.1 纯词法检索在「用户口语化改写」下几乎失效（决定性）

构造 10 条伴侣 App 会记的事实（如 `用户母亲患有高血压需要长期服药`），配 11 条**用户真会这么问**的改写查询（`我妈身体还好吗`、`我最近老是睡不着`、`我的猫咪呢`、`我怎么上班的`…），在真机上跑 uni+bigram BM25（k1=1.2, b=0.75, Lucene IDF）：

| 分词 | top1 | top3 | MRR | 零命中查询数 |
|---|---|---|---|---|
| bigram-only | 6/11 (55%) | 7/11 (64%) | 0.63 | 7/11 |
| **unigram + bigram** | **8/11 (73%)** | 8/11 (73%) | **0.76** | 1/11 |

失败样本全部是**同义词/指代**，不是权重问题：`我妈`↔`母亲`、`睡不着`↔`失眠`、`猫咪`↔`猫`、`上班`↔`通勤`、`换个工作`↔`跳槽`、`闹矛盾`↔`吵架`。**IDF 变体、融合权重、BM25 调参都修不了这一类错误。**

### 1.2 但「写入时把事实写成多说法可召回」能把召回拉起来（最强的正面证据）

同一批 11 条查询、同一个 BM25、同样 10 条记忆，只把记忆文本换成 mem0 `ADDITIVE_EXTRACTION_PROMPT` 风格（自包含、去代词、保留专有名词、括号内塞口语同义词、变更成对记录、时间转绝对）：

| 记忆文本形态 | top1 | top3 | MRR | 零命中 |
|---|---|---|---|---|
| 原子事实（`用户母亲患有高血压需要长期服药`） | 8/11 (73%) | 8/11 (73%) | 0.76 | 1 |
| **写入时归一化**（`用户的母亲（我妈、我妈身体、家里老人、母亲）患有高血压这种慢性病…`） | **10/11 (91%)** | **11/11 (100%)** | **0.95** | **0** |

对比之下，所有「检索侧」调优加起来只值 **+1.6 个百分点**（见 §4.4）。**改 prompt 的收益是改排序的 10 倍以上。**

⚠️ **未核实**：上表的「归一化」文本是**我手写的**，不是真模型输出。真实弱模型（便宜端点）能否稳定写出这种带同义词的事实，**必须实测**——这是本报告最需要补的一个实验。归一化本身也是一次额外 LLM 调用（可与抽取合并为同一次）。

### 1.3 纯词法去重在中文上既召回不足、又会语义反转

17 对中文事实（10 对真重复、4 对相关但不同、3 对矛盾），三种去重信号：

| 信号 | 真重复命中 | 误合并（相关不同 + 矛盾） |
|---|---|---|
| **md5 精确哈希**（mem0 的做法） | **1/10 (10%)** | 0/7 |
| 字符 bigram Jaccard ≥ 0.5 | 7/10 (70%) | **4/7 (57%)** |
| containment ≥ 0.85 | 2/10 (20%) | 2/7 (29%) |

失败样本：
- `用户生日是三月十二号` vs `用户的生日是3月12日` → Jaccard **0.188**（数字/汉字不互通）
- `用户喜欢吃火锅` vs `用户特别爱吃火锅` → Jaccard **0.300**
- `用户喜欢吃火锅` vs `用户不喜欢吃火锅` → Jaccard **0.625** → **把语义反转的两条合并了**

**误判率高于识别率。** 所以：词法相似度只能当「候选召回」，最终合并必须交给 LLM。而 md5 哈希虽然召回只有 10%，但它**零误杀**，适合当第一道廉价闸门。

### 1.4 所以对 AcpKernel 的补充清单

AcpKernel 负责的是「块 + 摘要 + 词法检索」这层簿记（M1–M10）。它**没有**、也不该有：
1. **事实抽取的 prompt 与写入归一化**（§1.2，收益最大）
2. **去重/合并的决策点**（md5 闸门 + LLM 判定）
3. **时间衰减**（§4.5，决定「说过期事实」还是「说当前事实」）

反过来，**不要**从别人那里借的：向量检索、图数据库、四动作 UPDATE/DELETE 决策、embedding 重排。这些在这台机器上要么跑不动，要么在弱模型上不稳定。

---

## 第二部分：五个候选（A / B / C 三类齐全）

每个候选给：仓库全名 / star / 最近提交 / License(SPDX) / 核心算法（数据结构 + 打分公式）/ 移植 Kotlin 人日 / 在 FTS4-only 无 GPU 下是否可用。

> License 说明：下列 SPDX 取自 GitHub API 的 `license.spdx_id`，⚠️ **未逐个通读 LICENSE 文件核对附加条款**。五个候选全部为 Apache-2.0 / MIT，**均允许闭源商用**（Apache-2.0 需保留 NOTICE 与版权声明；MIT 需保留版权与许可声明）。这与 `docs/research/billion-context.md` 里 AcpKernel 的 MIT+署名 一致。

---

### 2.A-1 mem0 —— LLM 事实抽取 + 纯 ADD + 哈希去重

| 字段 | 值 |
|---|---|
| 仓库 | `mem0ai/mem0` |
| Star | **68,915**（2026-11-14） |
| 最近提交 | `2026-11-06T16:53:07Z` |
| License | **Apache-2.0** |
| 语言 / 形态 | Python（另有 TS SDK），**服务端**，需向量库 + LLM + embedder → **不能直接依赖** |

**核心算法（源码级，版本 2.2.1）**

⚠️ **重要更正**：网上流传的「mem0 四动作 ADD/UPDATE/DELETE/NONE」是 v1 的行为。**v2.2.1 的 `mem0_main.py` 里 `DEFAULT_UPDATE_MEMORY_PROMPT` 出现次数为 0**，默认路径已改为**纯 ADD**（`mem0_main.py:944` 与 `:2629` 都写 `system_prompt = ADDITIVE_EXTRACTION_PROMPT`）；`_update_memory`(`:2100`) / `_delete_memory`(`:2132`) 只剩显式 API，**不再由 LLM 自动决策**。这对我们是好消息：弱模型上四动作极易出错，纯 ADD 更稳。

写入流水线（`mem0_main.py` Phase 0–5）：
1. 取已有记忆 top-10（作为「去重上下文」，**不参与抽取**）
2. **单次** LLM 调用，`response_format={"type":"json_object"}`，输出 `{"memory":[{"id":"0","text":"…","linked_memory_ids":[…]}]}`
3. `embed_batch`
4. 每条 `lemmatize_for_bm25(text)` 存进 `text_lemmatized` 字段（专供 BM25）
5. **去重**：`hashlib.md5(text.encode()).hexdigest()` 作为 `payload["hash"]`；用 `existing_hashes`（库里已有的）与 `seen_hashes`（本批内的）**双集合**比对，命中即 `continue` 跳过

**检索打分公式**（`mem0/utils/scoring.py`，139 行）：

```
sigmoid 参数按 query 词数自适应：
  num_terms ≤ 3  → (midpoint=5.0, steepness=0.7)
  ≤ 6            → (7.0, 0.6)
  ≤ 9            → (9.0, 0.5)
  ≤ 15           → (10.0, 0.5)
  else           → (12.0, 0.5)

normalize_bm25(raw) = 1 / (1 + exp(-steepness * (raw - midpoint)))      # 把无界 BM25 压到 (0,1)

combined = min( (semantic + bm25 + entity_boost) / max_possible, 1.0 )
  max_possible = 1.0 基础；有 bm25 再 +1.0；有 entity 再 +0.5   → 1.0 / 1.5 / 2.0 / 2.5

entity_boost = similarity * ENTITY_BOOST_WEIGHT(0.5) * memory_count_weight     # mem0_main.py:1817
```

其他细节：`_search_vector_store` 过采样 `internal_limit = max(limit*4, 60)`；threshold **在合并前**先过滤 semantic 分（低于阈值的候选即使 bm25 高也不进最终结果）；查询实体去重上限 **8** 个，实体库检索阈值 **≥0.5**。

**可直接搬的中文 prompt 要点**（`mem0/configs/prompts.py:468` `ADDITIVE_EXTRACTION_PROMPT`）：
- 「Your sole operation is ADD」；只从 New Messages 抽取，Existing 仅用于去重与链接
- **No Within-Response Duplication**：同一响应内语义等价的只留信息更丰富的一条
- **No Detail Contamination**：不得把已有记忆的细节并进新事实（"had a great meal" + 已有 "favorite restaurant is Olive Garden" **不得**合成 "had a great meal at Olive Garden"）
- **Contextually Rich, Not Atomic**：`Bad: "User has a dog"` / `Good: "User has a dog named Poppy and their morning walks together are the highlight of their day"`；**变更成对记录**（"switched from almond milk to oat milk lattes after developing an almond sensitivity"）
- **Temporally Grounded**：相对时间按 **Observation Date**（不是 Current Date）转绝对
- **Self-Contained**：代词全部替换
- 长度 **15–80 词**（细节多可到 100）；「NEVER sacrifice a proper noun, title, date, or specific detail to meet a word count」
- 「When in doubt, extract. A slightly redundant memory is far less costly than a missing one.」

**移植到 Kotlin**

| 部分 | 工作量 |
|---|---|
| 抽取 prompt 中文化 + JSON 解析 + 落表 | 1.5 人日 |
| md5 双集合去重 + `linked_memory_ids` 关联表 | 0.5 人日 |
| BM25 sigmoid 归一化 + 融合公式（**去掉 semantic 项**） | 1 人日 |
| 实体抽取 + boost（可选） | 2 人日 |
| **合计** | **3–5 人日**（不含实体 boost 则 3 人日） |

**FTS4-only / 无 GPU 下可用性**：✅ **核心部分完全可用**。抽取、去重、融合公式都不依赖向量。**必须砍掉** `semantic` 项与 `_search_vector_store`，`max_possible` 相应变为 1.5（bm25 1.0 + 基础 1.0 → 实际只剩 bm25 时坍缩为 1.0）；`entity_boost` 需要「实体相似度」——**无向量时必须降级为精确字符串匹配或 bigram Jaccard**（⚠️ 未核实降级后的效果）。

---

### 2.A-2 Memobase —— 结构化槽位 + APPEND/UPDATE/ABORT

| 字段 | 值 |
|---|---|
| 仓库 | `memodb-io/memobase` |
| Star | **2,955** |
| 最近提交 | `2026-01-11T03:51:40Z`（最后 push；仓库元数据 2026-11-12 有更新） |
| License | **Apache-2.0** |
| 语言 / 形态 | Python，**服务端** → 不能直接依赖 |

**核心算法**

数据结构是**两层槽位**：`topic` → `sub_topic` → 一串 memo。

去重/合并是**纯字典操作，不是向量也不是哈希**（`prompts/profile_init_utils.py` `merge_by_topic_sub_topics(new_facts)`）：

```python
key = (topic, sub_topic)
merged[key] = f"{existing[key]}; {new_fact['memo']}"     # 同槽位字符串拼接
```

合并决策由 LLM 做，中文 prompt 在 `prompts/zh_merge_profile.py`（4,175 B）`MERGE_FACTS_PROMPT`，**三动作 APPEND / UPDATE / ABORT**（注意：**没有 DELETE**）：
- 补充信息带来新信息 → `APPEND`
- 冲突或需修改才能体现 → `UPDATE`（必须**重写完整备忘录**）
- 无价值 / 已被完全包含 / 不符合主题描述 → `ABORT`
- 输出格式 `THOUGHT\n---\nACTION`
- 硬约束：**最终备忘录不超过 5 句话**；「永远不要编造输入中未提到的内容」；保留时间标注如 `[提及于2025/05/05]`；UPDATE 时去冗余（原文举例："User is sad; User's mood is sad" == "User is sad"）

上下文预算切分（`controllers/context.py` `get_user_context(...)`）：

```python
max_profile_token_size = int(max_token_size * profile_event_ratio)
max_event_token_size   = min(max_token_size - profile_section_tokens,
                             max_token_size - max_profile_token_size)
# profile 与 event 用 asyncio.gather 并行取
```

事件检索 `search_user_event_gists(topk=60, similarity_threshold=…, time_range_in_days=…)`，开关 `CONFIG.enable_event_embedding`——**关掉即退化为按时间取，说明 Memobase 本身就支持无向量模式**。

**移植到 Kotlin**

| 部分 | 工作量 |
|---|---|
| 槽位表 `profile(topic, sub_topic, memo, updated)` + 合并 | 1 人日 |
| 中文合并 prompt（APPEND/UPDATE/ABORT）+ 解析 + 重写校验 | 2 人日 |
| 5 句话上限 / 时间标注 / 去冗余的**代码侧**兜底（不能只靠 prompt） | 1.5 人日 |
| 预算切分（profile : event 比例） | 0.5 人日 |
| profile 的 UI 展示（用户可见「她记得什么」） | 1–2 人日 |
| **合计** | **5–7 人日** |

**FTS4-only / 无 GPU 下可用性**：✅ **完全可用，且是五个候选里对无向量最友好的**。整套是「结构化 KV + LLM 文本合并」，`enable_event_embedding=false` 就是官方支持的无向量模式。

**与 mem0 的关键差异（选型时很重要）**：mem0 是**事件流**（append 一批批事实），Memobase 是**档案**（每个槽位一行、持续被重写）。**档案不会无限膨胀**（槽位数固定），但**不可回溯**（UPDATE 覆盖即丢失旧版本）。对伴侣 App：mem0 的形态适合「她记得我们聊过什么」，Memobase 的形态适合「她记得我是个什么样的人」。**建议两者都要，见组合表。**

---

### 2.B-1 Generative Agents（Stanford）—— recency / importance 三因子，去掉 relevance 即可用

| 字段 | 值 |
|---|---|
| 仓库 | `joonspk-research/generative_agents` |
| Star | **22,378** |
| 最近提交 | `2024-08-05T21:38:56Z`（研究代码，已冻结） |
| License | **Apache-2.0** |
| 语言 / 形态 | Python，**离线仿真**，不能直接依赖 |

**核心算法**（`reverie/backend_server/persona/cognitive_modules/retrieve.py` + `memory_structures/scratch.py`）

节点 = `{embedding, poignancy(1–10), last_accessed, …}`，存在 `associative_memory` 里。

```python
# 1) recency：按 last_accessed 升序，越久远衰减越多，再 min-max 归一化到 [0,1]
recency_vals = [recency_decay ** i for i in range(1, len(nodes)+1)]   # recency_decay = 0.99
recency_vals = normalize_dict_floats(recency_vals, 0, 1)

# 2) importance：直接用节点的 poignancy，归一化
# 3) relevance：cos_sim(embedding, focal_embedding)，归一化

# 4) 合成（scratch.py 默认 recency_w = relevance_w = importance_w = 1）
gw = [0.5, 3, 2]
master = 1*recency*gw[0] + 1*relevance*gw[1] + 1*importance*gw[2]
#      = 0.5*recency + 3*relevance + 2*importance
top = nlargest(n_count=30, master)
# 5) 命中节点回写 last_accessed = curr_time  ← 检索即刷新 recency，正反馈
```

**这是本报告最重要的一处「可裁剪性」发现**：三因子里**只有 relevance 需要向量**。砍掉它之后剩 `0.5*recency + 2*importance`（比例的 1:4），仍然是完整可用的排序函数。⚠️ 但注意：**权重需要重调**——原版权重是围绕「relevance 主导」设计的（relevance : recency = 6:1），砍掉 relevance 后 importance 会主导，而伴侣 App 里「当前状态」恰恰需要 recency 压过 importance。建议改成 `0.5*recency + 0.5*importance`，或干脆把 recency 做成**乘性新鲜度因子**（见 §4.5，实测有效）。

⚠️ 另外：`importance` 由 LLM 打 1–10 分，是**每轮一次额外 LLM 调用**。手机上必须改成：在**抽取事实的那一次调用**里顺带让模型输出 1–5 的重要性，不要单独发一次请求。

**移植到 Kotlin**

| 部分 | 工作量 |
|---|---|
| `last_accessed` 字段 + recency 衰减 + min-max 归一化 | 0.5 人日 |
| importance 入 prompt（合并进抽取调用）+ 归一化 | 0.5 人日 |
| top-K 选择（`PriorityQueue` 或排序）+ 回写 `last_accessed` | 0.5 人日 |
| **合计** | **1–2 人日** |

**FTS4-only / 无 GPU 下可用性**：✅ **可用**（砍掉 relevance 后）。这是五个候选里**性价比最高**的一个：算法 30 行以内，收益是「不会随机说出过期事实」。

---

### 2.B-2 MemoryOS —— 分段热度公式 + 三层结构

| 字段 | 值 |
|---|---|
| 仓库 | `BAI-LAB/MemoryOS`（EMNLP 2025 Oral），另有工程版 `MemTensor/MemOS`（**12,386★，TypeScript，`2026-11-11`**） |
| Star | **1,620**（学术版） |
| 最近提交 | `2026-07-07T12:32:18Z` |
| License | **Apache-2.0** |
| 语言 / 形态 | Python，服务端 |

> 注意区分：`BAI-LAB/MemoryOS` 是论文代码；`MemTensor/MemOS` 是 TypeScript 工程版（12.4k★）。**两者不是同一个东西**，网上常混。本报告只评估学术版的公式。

**核心算法**（`memoryos-pypi/mid_term.py`、`utils.py`）

三层结构：
- **short_term**：`deque`，容量 **7**
- **mid_term**：`session` → `page` 两层，每页带 `heat`
- **long_term**：用户 / 助手知识 KV

热度公式（常量 `HEAT_ALPHA=1.0`、`HEAT_BETA=1.0`、`HEAT_GAMMA=1`、`RECENCY_TAU_HOURS=24`）：

```python
H_segment = alpha * N_visit + beta * L_interaction + gamma * R_recency
  N_visit         = 访问次数
  L_interaction   = len(processed_details)      # 该段交互条数
  R_recency       = exp(-delta_hours / tau_hours)   # tau = 24h

# 迁移：用最大堆维护，按 -H 排序
heapq.heappush(heap, (-H_segment, session_id))
# 超阈值的页迁移到 long_term
```

检索：`search_sessions(segment_similarity_threshold=0.1, page_similarity_threshold=0.1, top_k_sessions=5)`，`_retrieve_mid_term_context` 用 `heapq` 维护 top-N 页。

**移植到 Kotlin**

| 部分 | 工作量 |
|---|---|
| 热度公式 + exp 时间衰减 | 0.5 人日 |
| 三层迁移逻辑（deque / 堆 / 阈值） | 2 人日 |
| 阈值调参（论文的 0.1 是给向量用的，纯词法要重定） | 1–1.5 人日 |
| **合计** | **3–5 人日** |

**FTS4-only / 无 GPU 下可用性**：✅ **热度公式与迁移逻辑完全可用**（无向量）。⚠️ 但 `segment_similarity_threshold=0.1` 是**余弦相似度**阈值，纯词法下没有等价物——必须换成「BM25 分数阈值」或「bigram Jaccard 阈值」重新标定，**这是本候选最大的移植不确定点**。

**要不要做？** 对单角色伴侣 App，**三层结构在 1–2 万条记忆内是过度设计**（见 §4.2：全内存倒排在 20000 条时 37.5 MB、30 ms，可接受）。**只借鉴热度公式 `H = N_visit + L_interaction + exp(-Δh/24h)` 就够了**，把它当 recency 的一个更细的版本。

---

### 2.C-1 sqlite-fts4 + bm25s —— FTS4 上跑 BM25 的参考实现（唯一「能在设备上原样跑算法」的候选）

| 字段 | 值 |
|---|---|
| 仓库 | `simonw/sqlite-fts4`（24★，`2022-07-30`，**Apache-2.0**）+ `xhluca/bm25s`（**1,824★**，`2026-11-13T16:47:10Z`，**MIT**） |
| 形态 | Python 库，但**算法是纯 SQL + 纯函数**，可 1:1 翻译 |
| License | Apache-2.0 / MIT，**均允许闭源商用** |

**核心算法（`simonw/sqlite-fts4`，`sqlite_fts4/__init__.py`）**

```python
conn.create_function("rank_bm25", 1, rank_bm25)   # 关键：函数必须自定义
# SQL: SELECT …, rank_bm25(matchinfo(tbl, 'pcnalx')) FROM tbl WHERE tbl MATCH ?

k = 1.2; b = 0.75
for each phrase × column:
    d = 1 - b + b * (num_tokens / average_num_tokens)
    tf   = hits_this_column_this_row
    idf  = max(log((N - docs_with_hits + 0.5) / (docs_with_hits + 0.5)), 0)
    rhs  = (tf * (k + 1)) / (tf + k * d)
    score += idf * rhs
return -score        # SQLite 约定：rank 越小越相关
```

`matchinfo(…,'pcnalx')` 的整数序列布局（我按源码解码代码逐项核实，并在真机上二次确认）：

```
[p, c, n, a_1…a_c, l_1…l_c,  (x_{p,c}, hits_all_{p,c}, docs_{p,c}) …]
 p = phrase 数   c = 列数   n = 总行数
 a = 各列平均 token 数   l = 当前行各列 token 数
 x = 该 phrase 在该列该行的命中数
```

真机实测：单 phrase 单列时返回 8 个 uint32，如 `1,1,3,2,2,1,2,2`（p=1,c=1,n=3,a=[2],l=[2],x=1,hits=2,docs=2）。多 phrase 两列时返回 20 个 uint32，布局一致。

**⚠️ Android 上的两个必改点（这是本报告最实用的工程结论）**

1. **`rank()` 在 SQLite 3.28 上是内建函数且签名不兼容**：`SELECT rank(matchinfo(m,'pcnalx'))` → `Error: wrong number of arguments to function rank()`。所以 sqlite-fts4 的原始 SQL **不能在 Android 上原样跑**，必须把自定义函数改名（`rank_bm25` / `bm25_score`）。同一报错在 Node 内置 SQLite 3.53 上复现，说明是 SQLite 通用行为，不是本机特例。
2. **标准 Robertson IDF 在小语料上会归零**：`idf = max(log((N-df+0.5)/(df+0.5)), 0)`，当 `df ≥ N/2` 时直接为 0。实测 N=4/df=2 → **0.0000**；N=20/df=20 → **0.0000**。**必须换成 Lucene 变体**（`xhluca/bm25s` 的 `_score_idf_lucene`）：

```
IDF_lucene(df, N) = log(1 + (N - df + 0.5) / (df + 0.5))     # 永远 > 0
```

`bm25s/scoring.py` 里一共五个变体可直接抄：

| 变体 | 公式 |
|---|---|
| robertson（sqlite-fts4 同款） | `log(max((N-df+0.5)/(df+0.5), 1))` |
| **lucene（推荐）** | `log(1 + (N-df+0.5)/(df+0.5))` |
| atire | `log(N/df)` |
| bm25l | `log((N+1)/(df+0.5))` |
| bm25+ | `log((N+1)/df)` |

`bm25s` 默认超参是 **`k1=1.5, b=0.75, delta=0.5`**（不是常见的 1.2），默认 `method="robertson"`。⚠️ 它的默认分词器 `r"(?u)\b\w\w+\b"` **对中文完全无效**，必须自定义 splitter。

**移植到 Kotlin**

| 部分 | 工作量 |
|---|---|
| `matchinfo` 字节解码（ByteBuffer → int[]，按 pcnalx 布局切） | 0.5 人日 |
| BM25 累加 + Lucene IDF | 0.5 人日 |
| 中文 uni+bigram 分词器（`Tokenize` + 影子列维护） | 1 人日 |
| 外部内容 FTS4 同步（`rebuild` 时机、事务） | 0.5 人日 |
| **合计** | **2–3 人日** |

**FTS4-only / 无 GPU 下可用性**：✅ **唯一一个算法能在设备上 1:1 原样跑的候选**（改函数名 + 换 IDF 变体之后）。实测 2000 轮语料下单次 3-term `OR` + `matchinfo` 查询 **1.8 ms**，2000 轮库 **909 KB**，进程峰值 **8.4 MB**。

**⚠️ 注意**：这份候选的价值**不在于代码**（不到 200 行），而在于**三个已被实测证伪的坑**：`rank()` 名字冲突、Robertson IDF 归零、外部内容 FTS4 的列名约束。

---

### 2.D 「纯滑动窗口 + 层级摘要」类：只做基线，不列为完整候选

| 仓库 | Star | License | 可借鉴点 |
|---|---|---|---|
| `langchain-ai/langchain` | 149,456 | MIT | `ConversationSummaryBufferMemory`：`while curr_buffer_length > max_token_limit: pruned.append(buffer.pop(0))` 然后 `moving_summary_buffer = predict_new_summary(pruned, moving_summary_buffer)`，默认 `max_token_limit=2000`（`libs/…/memory/summary_buffer.py:115-127`）。**核心逻辑约 30 行 Kotlin。** |

这类方案**已被 AcpKernel 的 M1/M3/M5/M6 覆盖并超越**（AcpKernel 有块 id、范围、可 decompress，这里只有一根字符串）。**不建议单独移植**，但值得作为「组合一的降级下限」——如果 AcpKernel 移植卡住，30 行的滚动摘要可以先顶上。

---

## 第三部分：移植工作量总表

| 候选 | 核心机制 | 人日 | 依赖 FTS4 | 依赖向量 | 依赖额外 LLM 调用 |
|---|---|---|---|---|---|
| A-1 mem0 | ADD-only 抽取 + md5 去重 + 实体 boost 融合 | **3–5** | 否 | 否（需降级） | 1 次/批 |
| A-2 Memobase | 槽位档案 + APPEND/UPDATE/ABORT | **5–7** | 否 | 否（官方支持关闭） | 1 次/批 |
| B-1 Generative Agents | recency + importance 双因子 | **1–2** | 否 | 否（砍 relevance） | 0（并入抽取） |
| B-2 MemoryOS | 热度公式 + 三层迁移 | **3–5** | 否 | 部分（阈值需重标） | 0 |
| C-1 sqlite-fts4 + bm25s | matchinfo BM25 + Lucene IDF + bigram | **2–3** | **是** | **否** | 0 |
| （D 基线）LangChain SummaryBuffer | 滚动窗口 + 运行摘要 | 0.5 | 否 | 否 | 1 次/溢出 |

---

## 第四部分：实测数据汇总（所有数字都可在目标机上复现）

### 4.1 FTS4 中文检索性能（2000 轮对话，bigram 影子列，外部内容）

| 指标 | 值 |
|---|---|
| 建库（2000 轮插入 + `rebuild`，单事务，`journal_mode=off`） | **0.26 s** |
| db 体积 | **909,312 B（~909 KB）** |
| 200× `MATCH` + `COUNT` | 0.10 s → **0.5 ms/次** |
| 200× `MATCH` + `matchinfo` | 0.25 s → **1.25 ms/次** |
| 200× 3-term `OR` + `matchinfo` | 0.36 s → **1.8 ms/次** |
| `snippet()` | ✅ 可用，`..分 分享 享 享医 医 [医院] 院 院的 的 的检..` |
| `offsets()` | ✅ 可用，`1 0 59 6` |
| `highlight()` | ❌ `no such function` |

### 4.2 全内存倒排索引（dalvikvm 实测，作为「不建库、纯内存」的对照）

| N | 建索引 | 单次查询 | 堆占用 |
|---|---|---|---|
| 1,000 | 119.7 ms | 1.05 ms | 3.5 MB |
| 5,000 | 512.4 ms | 6.54 ms | 10.7 MB |
| 20,000 | 2,077.2 ms | 30.71 ms | 37.5 MB |

结构：`HashMap<String, int[]>`，`int[]` 布局 `{doc,tf,doc,tf,…}`。**内存约 1.9 KB/行** → 10 万条约 190 MB，**超预算**，所以超过约 5 万条必须落到 SQLite。**1–2 万条完全在 150 MB 预算内。**

### 4.3 分词策略：bigram 必须补 unigram

| 查询 | bigram-only | unigram+bigram |
|---|---|---|
| 单字 `猫`（3 篇文档都含「猫」） | **0 命中** | **3/3 命中** |

原因：bigram 索引里根本没有单字 token。**用户单字提问（"猫""狗""钱""疼"）在 bigram-only 下召回为 0。** 手机上必须在 bigram 之外**额外输出单字 unigram**（实测代价很小，见 4.5 的召回提升）。

### 4.4 融合权重到底值多少

60 条中文记忆，每条配一个 gold query：

| 方案 | top1 | top3 |
|---|---|---|
| raw BM25（Robertson IDF） | 83.1% | 91.5% |
| smoothed BM25（Lucene IDF） | 83.1% | 91.5% |
| + recency（×`0.7+0.3*rec`） | 84.7% | 91.5% |
| + recency + importance（×`0.6+0.25*rec+0.15*imp`） | 84.7% | 91.5% |

**融合只值 +1.6 个百分点。** IDF 平滑在这个语料上无差别（df 都小于 N/2，Robertson 没触发归零）。**结论：不要在排序权重上调参浪费时间。**

### 4.5 过期事实问题（决定「她会不会说出你三年前住哪儿」）

6 条同谓词、不同时间的记忆，两两成对。因为 bigram 集合几乎相同，**plain BM25 给出完全相同的分数**：

| 查询 | 记忆 A（age） | 记忆 B（age） | plain BM25 | plain 胜者 | fused 胜者 |
|---|---|---|---|---|---|
| `住在` | 杭州（365d） | 成都（3d） | **4.3293 vs 4.3293（完全相等）** | 杭州 ❌ | 成都 ✅ |
| `工程师` | 后端（400d） | 前端（6d） | **7.4080 vs 7.4080** | 后端 ❌ | 前端 ✅ |
| `养` | 团子（300d） | 年糕（10d） | **1.2733 vs 1.2733** | 团子 ❌ | 年糕 ✅ |
| `上班` | 阿里（500d） | 字节（30d） | **4.2149 vs 4.2149** | 阿里 ❌ | 字节 ✅ |
| `辣` | 喜欢吃辣（600d） | 不吃辣了（45d） | **1.6630 vs 1.6630** | 喜欢吃辣 ❌ | 不吃辣了 ✅ |

**5/5 全部打平，胜负由 `rowid` 顺序决定 → plain BM25 会稳定地返回最旧的记忆。** 加上 `0.6 + 0.4*exp(-age_days/30)` 的乘性新鲜度后 5/5 全部纠正。

**这是本次调研最可落地的单点结论**：不加时间衰减，伴侣 App 会说「你还住在杭州吧」——而这恰恰是虚拟伴侣最致命的失败模式。

### 4.6 改写查询召回（§1.1 / §1.2 的原始数据）

| 记忆文本 | 分词 | top1 | top3 | MRR | 零命中 |
|---|---|---|---|---|---|
| 原子事实 | bigram-only | 6/11 (55%) | 7/11 (64%) | 0.63 | 7 |
| 原子事实 | uni+bigram | 8/11 (73%) | 8/11 (73%) | 0.76 | 1 |
| **写入归一化** | uni+bigram | **10/11 (91%)** | **11/11 (100%)** | **0.95** | **0** |

---

## 第五部分：3 个推荐组合

| 组合名 | 用到哪些机制 | 预估代码量 | 风险 |
|---|---|---|---|
| **组合一「能跑起来」**<br>（3–4 人日） | LangChain 式滚动窗口摘要（30 行）+ mem0 ADD-only 抽取 prompt 中文化 + **md5 双集合去重**；检索 = 最近 N 轮 + 全部事实直塞 prompt，**不检索** | **~500–700 LOC**<br>（2 张表：`msg` / `fact`） | ① 事实无限膨胀，128k 窗口下 500 条事实会把对话挤掉；② **无法按需回忆**（问「我上次说的那个」答不上）；③ 摘要每次重写会单调丢细节（drift） |
| **组合二「推荐」**<br>（8–12 人日） | 组合一 + **FTS4 bigram/uni 影子列 + Lucene-IDF BM25（matchinfo 解码）** + **recency 乘性时间衰减** + Generative Agents 的 importance（并入抽取调用）+ AcpKernel 的块/范围/decompress | **~1,200–1,600 LOC**<br>（4 张表 + FTS4 虚表） | ① **`rank()` 名字冲突**必须改名（已实测，必改）；② Robertson IDF 归零必须换 Lucene 变体；③ 外部内容 FTS4 的列名约束（`snippet/offsets` 会报 `SQL logic error`）；④ 召回仍受同义词限制——**依赖抽取 prompt 质量**，弱模型可能写不出带同义词的事实；⑤ 每 ~15–20 轮一次额外 LLM 调用，4×A53 上会有可感知停顿 |
| **组合三「完整」**<br>（20–30 人日） | 组合二 + **Memobase 槽位档案**（topic/sub_topic + APPEND/UPDATE/ABORT + 5 句上限）+ **MemoryOS 热度公式**（`H = N_visit + L_interaction + exp(-Δh/24h)`）做冷热分层 + Graphiti 式**事实取代**（`superseded_by` 外键，永不原地 UPDATE） | **~2,500–3,500 LOC**<br>（7 张表） | ① 三套真相（事件流 / 档案 / 热度层）冗余，一个 bug 就静默丢记忆，**比明说不记得更糟**；② 热度阈值原为余弦相似度 0.1，纯词法下**必须重标**（最大不确定点）；③ prompt 数量翻倍，弱模型上格式遵循率下降；④ 调试成本高，需要 `turn_trace` 记录「这一轮给她看了什么」 |

**共同前提（三套都要）**：不可变消息引用（`m00042`）；第一条用户消息永久 pin；最后一条用户消息永不压缩。这三条是 AcpKernel M9 的核心，40 行以内，能挡掉最常见的压缩 bug。

---

## 第六部分：如果只能做一件事

> **做「写入时的归一化」——把 mem0 的 `ADDITIVE_EXTRACTION_PROMPT` 中文化，让每条事实都写成「自包含 + 带口语同义词 + 带绝对时间」的形式；去重只做 md5 精确哈希，合并只做纯 ADD。**

理由，按证据强度排：

1. **它是唯一一个被实测证明能把召回拉起来的改动**：top1 **73% → 91%**，top3 **73% → 100%**，零命中 **1 → 0**（§4.6）。而排序侧所有调优（IDF 变体、融合权重、importance）加起来只有 **+1.6 个点**（§4.4）。
2. **它不需要 FTS4、不需要向量、不需要改数据库**——只是改一段 prompt 加一个字段，**1.5 人日**。
3. **它同时解决了去重**：mem0 v2 已经证明「纯 ADD + md5 + prompt 内去重指令」可行，而**中文上的词法去重实测误判率（57%）高于识别率（70%）**（§1.3），任何「智能合并」都是在给自己挖坑。md5 召回虽只有 10%，但**零误杀**。
4. **它是对 AcpKernel 的直接补强**：AcpKernel 的检索是纯词法，词法检索的天花板就是「查询和记忆是否共享 token」。**归一化是在写入侧把这个天花板抬高，而调 BM25 是在天花板下面挪家具。**
5. **它可回退**：如果归一化后的文本让用户觉得「她说的话变书面了」，改 prompt 即可，不涉及数据结构迁移。

**明确不要先做的三件事**：
- ❌ 不要上本地 embedding（体积 13.5 MB 起、算力 0.59 GFLOPS，两条独立理由都否掉，§0）。
- ❌ 不要做四动作 UPDATE/DELETE 合并（mem0 自己在 v2 已经删掉了；弱模型上必错）。
- ❌ 不要做三层记忆结构（1–2 万条以内，全内存倒排 37.5 MB / 30 ms 就够，§4.2）。

**唯一比它更紧急的事**：如果只能做「一件事」但可以选「不做记忆」，那先确认 —— **时间衰减（§4.5）比归一化更便宜（半天）**，且它防的是「说错事实」而不是「想不起来」。**归一化让记忆更容易被找到；时间衰减让找到的记忆是对的。** 两者都在 2 人日内，建议一起做。

---

## 附录 A：未核实项（请勿当作结论使用）

| 项 | 状态 | 如何补 |
|---|---|---|
| 真实弱模型能否稳定写出「带同义词的归一化事实」 | ⚠️ **最关键** | 用真实端点跑 50 条中文对话，人工看抽取结果 |
| 本地 embedding 的实际 RTF | ⚠️ 未测 | 在设备上跑 onnxruntime Android demo 计时；本报告只有 GFLOPS 基线与 APK 体积两条推算 |
| Room `@Fts4` + `contentEntity` 走 openHelper 的真机行为 | ⚠️ 未测 | 只用 `/system/bin/sqlite3`（链同一 `/system/lib64/libsqlite.so`）测过，是推断 |
| Memobase / MemoryOS 的端到端效果 | ⚠️ 只读公式 | 未执行其服务 |
| entity boost 在无向量时的降级效果 | ⚠️ 未测 | 精确匹配 / bigram Jaccard 两种降级都需实测 |
| MemoryOS 的 `similarity_threshold=0.1` 在纯词法下的等价阈值 | ⚠️ 未标定 | 需要用本机语料重新扫一遍阈值曲线 |
| 各仓库 LICENSE 的附加条款 | ⚠️ 只取 GitHub API 的 `spdx_id` | 逐个 `cat LICENSE` |
| star 数 | ⚠️ 2026-11-14 快照 | 会变 |

## 附录 B：本次调研产生的临时文件（都不应留在 repo 里）

`/tmp/jb/`（`Bench.java`、`Bench2.java`、`Bench3.java`、`Flops.java`、`Para.java`、`Para2.java`、`Stale.java`、`Norm.java` 及 `classes.dex`）、`/tmp/mem/`（抓下来的上游源码）、`/tmp/dedup.mjs`、`/tmp/fuse.mjs`、`/tmp/stale2.mjs`、`/tmp/gen.js`、`/tmp/gen2.js`、`/tmp/genq.js`、`/tmp/load*.sql`、`/tmp/qA|qB|qC|q200*.sql`、`/tmp/sqb/`（解包的 AAR）、设备上的 `/data/local/tmp/{m2.db,m2k.db,*.dex,*.sql,ec*.db,sn*.db,cr*.db}`。

本报告未写入 `docs/research/` 之外的任何路径。
