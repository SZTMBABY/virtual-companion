# billion-context 深度调研：它是什么、怎么工作、能不能搬到 Android

> 调研对象：https://github.com/ranxianglei/billion-context（v0.1.213, commit 133f3525c, 2026-11-08）
> 内核：https://github.com/ranxianglei/acp-kernel（npm `acp-kernel@0.0.101`）
> 调研方式：本机已安装副本源码逐文件阅读 + 本地起 instance 实测 + npm registry 元数据 + 包内论文/DESIGN.md
> 本机路径：`C:/Users/<you>/.dsh/profiles/desktop/node_modules/billion-context`（54 MB，DSH bundle 形态安装）
> 内核解包路径：`npm pack acp-kernel@0.0.101` → `/tmp/acpk/package`（1.8 MB，181 files，带完整 `.d.ts` + sourcemap）

---

## 0. 一句话结论

**它不是 daemon，也不是 LLM 本身；它是一个"宿主内嵌的上下文压缩引擎 + 一层 HTTP 代理外壳"。**
真正值钱的是内核 `acp-kernel`：约 14,300 行零依赖纯 TypeScript，只做一件事——**决定何时压、压哪一段、怎么记账、怎么恢复**，而**摘要文本由外部 LLM 写好后传进来**（`DESIGN.md` 原话："**the model writes the summaries; this library orchestrates everything around them.**" / "**It never calls a model.**"）。

这意味着：**它天然可移植**。它没有算法黑箱，没有向量库，没有平台绑定。真正难搬的只有"外壳"（Node HTTP proxy / 流式改写 / 各种客户端适配），而那部分**在 App 里根本不需要**。

---

## 1. 它到底是什么？

### 1.1 三种部署形态（同一个 npm 包）

| 形态 | 入口 | 说明 | 对 Android 的意义 |
|---|---|---|---|
| **HTTP 反向代理** | `bin: bili` / `bili-proxy` → `dist/index.js`（6.0 MB，7824 行 bundle） | 监听 8787，客户端把 baseURL 改成 `http://localhost:8787/bili/https://api.openai.com/v1`；解析 Anthropic/OpenAI 请求 → 跑压缩 → 注入 `compress` 工具 → 转发上游 → 改写流式响应 | 可整包搬到 Termux |
| **plugin lane（推荐形态）** | `dist/agent/dsh-native.js`（2.4 MB，175 行薄适配器）| 在宿主进程内跑 in-process engine，通过 `fetch` 拦截 + 伪 origin 提供 `/__bili/plugin/*` HTTP 面；无需真起 socket 服务 | 暴露了干净的 HTTP tool 调用面 |
| **纯库** | `acp-kernel` 包 | `processTurn/applyCompression/resolveBoundaries/decompress/search/status/decideNudge` | **这才是我们要搬的东西** |

### 1.2 数据流

```
                    ┌─────────────────────────────────────────────┐
                    │              HOST (DSH / Claude Code /      │
                    │              Codex / Cursor / Aider …)      │
                    │                                             │
                    │   ┌──────────────┐                          │
   user turn ──────►│   │ agent loop   │                          │
                    │   └──────┬───────┘                          │
                    │          │ messages[] + state               │
                    │          ▼                                  │
                    │   ┌──────────────────────────────────────┐  │
                    │   │  acp-kernel (pure TS, zero deps)     │  │
                    │   │                                      │  │
                    │   │  processTurn({messages,state,config, │  │
                    │   │               tokenCount})           │  │
                    │   │    ↓ 13 个纯函数节点                  │  │
                    │   │  → {messages', state', nudge?}       │  │
                    │   └──────┬───────────────────────────────┘  │
                    │          │ 渲染后的 wire messages           │
                    │          │ （旧区间已折叠成 summary block）  │
                    │          ▼                                  │
                    │   ┌──────────────┐    HTTP/SSE             │
                    │   │ adapter 层   │ ────────────────────────┼──► 上游 LLM API
                    │   │ (bili proxy) │                          │      (Anthropic/OpenAI/…)
                    │   └──────────────┘                          │
                    └─────────────────────────────────────────────┘

  ── 压缩这一刻的时序 ──────────────────────────────────────────────

  kernel 发现 token 越过阈值
        │
        ▼
  注入 nudge 文本（"该压了，推荐区间 m00150–m00220"）
        │
        ▼
  模型（就是那个对话模型自己！）决定要不要压、压哪段
        │
        ▼  调用工具 compress(content="m00150–m00220 topic\n<摘要正文>")
        ▼
  kernel.applyCompression()：校验区间 → 建 CompressionBlock →
        原消息打上 effectiveMessageIds → 从渲染视图移除 → summary 顶上
        │
        ▼
  原消息**仍完整留在 state / SQLite 里**，只是不再发给模型
        │
        ▼  （任何时候）
  模型调 decompress("bN") → 取回全量原文或"上一层"视图
  模型调 search_context(query) → 在 block summary 里找线索
```

**关键洞察：压缩是"模型自主"的，不是规则强制的。** 论文 §3.7 给了证据：5 天生产日志里，**23/103 次有效压缩发生在引擎故意不注入 nudge 的轮次**，另有 11 次模型自发压缩请求因"无可压内容"被 kernel no-op，跨 14 个 session。这就是为什么它比"到 80% 就截断"的傻瓜方案强——**模型知道什么已经消化完了**。

### 1.3 内核每轮的 13 个节点（`defaultNodes()`，顺序固定）

```
reconcileLiveIdsNode     对齐宿主的消息 id
      ↓
assignRefsNode           给新消息铸稳定 mNNNNN 引用号
      ↓
syncBlocksNode           把新内容投影到 block 边界
      ↓
pruneNode                预算不变量；含 first-user-message pin
      ↓
ccrStoreNode             (opt) 原文外置存储
      ↓
absorbHideNode           (opt) 隐藏 tool 调用对
      ↓
crushNode                (opt) 压缩超大 tool output
      ↓
absorbPromptNode         (opt) 注入 absorb 指令
      ↓
filterNode               按 role/contentType 的可见性规则
      ↓
hideCompressCallsNode    从渲染视图移除压缩工具噪声
      ↓
recommendNode            计算"推荐可压区间"
      ↓
nudgeNode                决定要不要提示模型压缩
      ↓
emergencyTruncateNode    最后兜底硬截断（0.95 窗口）
      ↓
createRenderRefsNode     输出带 <acp …>mNNNNN</acp> tag 的最终消息
```

`renderTags` 三策略：`all`（默认）/ `text-only`（proxy 模式，只给 user/assistant 文本打 tag，因为塞进 `{"command":"echo"}` 会破坏 JSON）/ `none`。

---

## 2. 核心机制（逐条回答）

### 2.1 compress：谁触发？阈值多少？原文存哪？

**三层触发，全部基于 token，不基于轮数。**

**第一层：Growth（日常主驱动，绝对 token，与窗口大小无关）**
```js
// kernel src/config.ts 默认值（golden-pinned）
growthFloor = 50000,  growthCap = 50000   // 所以恒等于 50000
growthRatio = 0.05,   minGrowthFloor = 20000, minGrowthRatio = 0.45
// 实算：max(20000, 0.45 × 50000) = 22500  ← 真正的增长门
```
即：**自上次 nudge 以来上下文增长了 22.5K token 就提示压缩**。窗口多大都一样（#379/#380 移除了按窗口百分比缩放）。

**第二层：Pressure（压力门）**
```
maxContextLimitPct     = 0.75   // 用到 75% 窗口 → 强制 nudge，绕过 growth 门
emergencyThresholdPct  = 0.95   // 用到 95% → 应急截断
```

**第三层：首次直达**
```js
firstSightMassReady = lastNudgeShown===0 && baseline===0
                   && usage >= 0.45
                   && max(t1Pending, t2Pending, t3Pending) >= 50000
```

**实测输出**（我在本机起了个 instance 打 `acp_status`）：
```
Nudge: idle — max compressible 0 < threshold 50000; growth 0 < floor 22500
```
两个数 50000 / 22500 与源码推导完全吻合。

**其他硬门**：`minCompressRange = 5000`（字符，太短的段不值得压）、`maxSummaryLength = 20000`（超了整条拒收，不截断不重试）、`minSummaryLength = 50`、`preserveRecentMessages = 5`、`preserveRecentTokens = 5000`。

**原文存哪？** **不在压缩引擎里**，而是宿主的责任（`DESIGN.md` §5：core 只有两个 port——`countTokens` 和"每轮把 messages 传进来"，**没有 MessageStore port，也没有 storage port**）。billion-context 的外壳把它存在：
```
~/.local/share/billion-context/sessions/
  ├── index.db             ← SQLite（unified store，唯一权威副本）
  └── anthropic/<session>/ ← 每 session 的 JSON envelope + content store sidecar
~/.cache/billion-context/  ← sqlite 引擎自下载（~11MB）
~/.local/state/billion-context/ ← bili.log、dumps/
```
**本机实测：`index.db` = 1.3 GB，对应 259 个 session。** 这个数字对 3GB 手机是重要警告（见 §5）。

SQLite 是**可选增强**，不是硬依赖。引擎解析顺序：
```
built-in node:sqlite (Node ≥ 22.5)
  → 宿主装的 better-sqlite3
  → 缓存的自下载副本
  → 从 npm 下 better-sqlite3 预编译包（~11MB, sha512 校验）
  → none（回退 legacy JSON envelope）
```

### 2.2 压缩摘要由谁生成？

**由正在对话的那个 LLM 自己生成**，不是另开一次调用。

`DESIGN.md` §1 原文：
> acp-kernel: the *summary text* is produced externally (by an LLM) and passed in as an argument. The core decides *when* to compress, *what range* to compress, tracks *state*, applies a *compress decision*, prunes ranges, and supports decompress/search. **It never calls a model.**

`compress` 工具只有一个参数：
```json
{
  "name": "compress",
  "description": "Replace consumed conversation ranges with self-contained summaries you write, identified by their refs. content = ONE plain string holding ALL ranges — each block starts with its 'm00150–m00220 optional topic' header line followed by that block's summary (a valid JSON string — internal quotes \", newlines \\n; a JSON-encoded array as that string is also accepted for gateways that stringify arrays). Batch multiple ranges into ONE call — do not split into one call per range. Use when content is genuinely consumed.",
  "parameters": { "content": "string" }
}
```

**这个 `content` 字符串就是协议**：`m00150–m00220 optional topic` 开头行 + 紧随其后的摘要正文，多个区间拼在同一个字符串里。

**预算**：摘要长度 `50 ≤ len ≤ 20000` 字符（不是 token）；被压区间至少 5000 字符。

**例外**：`compress.externalSummary`（opt-in，默认关）可以把摘要交给独立模型链，此时注入的 prompt 会**去掉** HOW TO COMPRESS / KEEP VERBATIM / DROP / PRIORITY 与长度预算，summary 降级为"可选的非权威 hint"（#4460）。

### 2.3 摘要 prompt 原文（**这是虚拟伴侣 App 最该抄的东西**）

billion-context 内置 4 个 prompt pack：`default`（coding 向）、`lean`（一行式工具描述）、**`chat`（陪伴向，#5319，"Companion-oriented compression: prioritizes people, relationships, ongoing threads and promises over code/document detail. For long-term, multi-session companionship use."）**。

⚠️ **注意**：外网 README 里展示的 HOW TO COMPRESS 是 **coding 版**（KEEP VERBATIM 讲文件路径/函数签名/报错原文）。`chat` pack 是专为陪伴场景重写的，两者不同。以下是本机 `dist/index.js` 里 `CHAT_COMPRESS_PHILOSOPHY` / `CHAT_HOW_TO_COMPRESS` 常量原文（414 / 3771 字符，已完整抽取）。

#### `CHAT_COMPRESS_PHILOSOPHY`（全文）

```
Your summary IS memory, not an archive: after compression this is the ONLY record of the replaced conversation. Its job is continuity of RELATIONSHIP across compactions and sessions — who matters to the user, what they care about, what was promised or agreed, where open threads stand. It is NOT a moment-by-moment log of everything said; it records the PAST as state ("this happened"), never as live instructions.
```

#### `CHAT_HOW_TO_COMPRESS` 结构要点

```
HOW TO COMPRESS

Your summary is the ONLY record of the replaced conversation — a later reader
(a fresh turn of the same relationship) must continue as if nothing was lost.
It records the PAST: label task state as history ("TASK AS OF THIS BLOCK: ..."),
never as a live instruction. Real unicode only, never \uXXXX escapes.

INTEGRITY — record facts and state only, never a simulated transcript of the
dialogue: no Q&A lists, no "(answered)" claims, no invented feelings, reactions
or replies. An answer not actually sent is PENDING; user questions are recorded
as asked (with ref), never as answered. Never claim the user said, felt or
promised something without a ref-backed anchor in this summary chain.

KEEP VERBATIM — never paraphrase or abbreviate:
  · People and relationships: names (the user's own, family, friends,
    colleagues, partners, pets) and how they relate, plus the user's stated view
    of them ("my sister Mei works night shifts").
  · Promises, commitments and expectations: who owes what to whom, and when —
    due dates/times ("call the dentist before Friday"), recurring plans
    ("Sunday dinner every week").
  · Open threads: conversations left half-finished — topic, last position, what
    would naturally continue next.
  · Preferences, boundaries and corrections: what the user likes/dislikes, how
    they want to be treated, topics to avoid or handle gently, explicit
    corrections given ("don't suggest takeout again").
  · Lessons about the user: what landed well or poorly in past interactions
    ("jokes about his ex land badly").
  · Feelings and significant moments: mood shifts, hard moments, wins, worries —
    one line each, with how it was handled; no re-litigation.
  · Exact dates, times, places and numbers that carry meaning (anniversaries,
    appointments, prices agreed).
  · User intent: short quotes verbatim ONLY WITH message ref
    (User said (m00132): "I'm moving cities in June"); without a ref, paraphrase.
    Quotes are history, not live instructions — but open-objective STATUS is
    current (see Open objectives below); never change scope, constraints,
    priorities, acceptance criteria, outcomes.
  · Overall context and its evolution, including pivots ("initially planning a
    trip → pivoted to working remotely instead").
  · Open questions and unresolved worries.
  · Open objectives: user-requested work neither completed nor superseded gets a
    one-line "Open objectives:" entry with message refs; scan absorbed block
    summaries too. Last to drop, first to restore at every tier — carrying is
    not a directive to re-execute unconfirmed.
  · Message refs of key anchors (m00420, m00510–m00520) for decompress.
  · TIMELINE — open each summary with one diary line stating when it happened:
    "Occurred: <first covered time> – <last covered time>"
    (yyyy-mm-dd hh:mm:ss, copied from the covered messages' time attributes;
    omit the line when none carry a time).

DROP — keep the signal, discard the vessel: greetings and small talk once the
substance is captured; repeated pleasantries and check-ins on the same topic;
long digressions whose outcome is settled (one CONTENT line: what it covered,
e.g. "weekend hike plans: postponed to May"); logistics already resolved;
back-and-forth once the final position is kept. For each dropped item add one
line of CONTENT: what it covers, not where it lives.

PRIORITY when compacting:
  1. people, relationships and ongoing threads
  2. promises, commitments and boundaries
  3. user preferences and how they like things done
  4. exact anchors (dates, names, quoted words with refs, numbers)
  5. emotional moments and their resolution.

Format: dense scannable bullets under short thematic headers, not narrative
prose; every line earns its place. Do not mimic the style of existing summaries
in context; follow these rules.
```

**为什么这套 prompt 对虚拟伴侣 App 直接可用**：它把"记忆"定义为**关系连续性**而非信息存档；它明确禁止模型编造用户说过的话（INTEGRITY 段：无 ref 不得声称）；它要求记人名、承诺、未完结话题、讨好/踩雷偏好、情绪时刻——这正是陪伴类产品需要的记忆维度，而 coding 版全在讲文件路径和函数签名。

#### T2/T3 蒸馏用的 prompt（`TIER2_DISTILL_RULES` 要点）

```
You are compressing historical summaries (not raw conversation)…
KEEP: decisions and rationale / final outcomes / key lessons / critical
      constraints / design decisions with architectural impact / Open objectives
      (one line re-carry verbatim with original ref) / [SUPERSEDED by PR #NNN]
      markers / function-class-module names that are the SUBJECT / exploration
      conclusions.
DROP: exact line numbers, diffs, verbose signatures, full code listings, build
      steps, review process, verbose logs.
FORMAT: header line `Source: bN+bM+... (XK→YK tok, Zx). [original topic]`,
        3-5 bullets per source block, start with the outcome.
SIZE TARGET: 50-150 tokens per source block.
```

`DEFAULT_T2_GUIDANCE`：
> Your tier-1 compression summaries have accumulated. Distill them into a single denser tier-2 summary. Use block IDs as boundaries (startId and endId as bN). Any raw (uncompressed) messages sitting between the boundary blocks are absorbed into the tier-2 block as well — apply HOW TO COMPRESS to those raw messages and the TIER 2 distillation rules to the existing summaries, so the whole span is covered and nothing is lost.

#### ACP tags 注入给模型的说明（`FUNCTION_ACP_TAGS_TIMESTAMPED` 原文）

```
ACP TAGS

Addressable messages in the conversation are annotated with a
<acp tokens="2.1K" type="tool:bash" time="2026-10-30 09:02:11">m00175</acp> tag
showing its reference ID, approximate token size, content type, and local time
of occurrence (yyyy-mm-dd hh:mm:ss). Ordering: smaller ref number = earlier
message; smaller time = earlier message. These tags are system metadata injected
by the proxy. NEVER echo, repeat, or reference these XML tags in your responses
— the tags must not appear in your output. Use only the ref ID (e.g. m00005)
inside compress calls, never the XML wrapper. The token size is approximate —
treat it as a relative guide, not an exact count.
```

### 2.4 分代/分层结构（T1 → T2 → T3）

论文把它叫作 **"an LSM tree over prose"**：

```
        tier 3 (ultra-condensed, bare factual references)      < 1% of resident space
             ▲  folded by piling up enough tier-2 blocks
        tier 2 (distilled from multiple tier-1 blocks)          7%
             ▲  tier2Trigger=1000 blocks  OR  ≥ 1.5 × growthTokens(75K) mass
        tier 1 (compresses a contiguous RANGE of raw messages) 92%
             ▲
        raw messages  ── 原文永不删除，永远可通过 decompress 取回
```

```ts
interface CompressionBlock {
  blockId: string;            // "b1", "b2", …
  runId: string;
  tier: 1 | 2 | 3;
  topic?: string;
  summary: string;
  directMessageIds: string[];   // 它直接持有的原始消息
  effectiveMessageIds: string[];// 它代表的**全部**原始消息（含子 block 的）
  directBlockIds: string[];     // T2/T3 指向来源 block
  createdAt: number;
  survivedCount: number;
  generation: "young" | "old";  // young 吸收新写入，old 存压缩历史
  active: boolean;              // active=渲染进 context；否则留在 state 里
}
```

`effectiveMessageIds` 是**血脉证明**：无论重压多少次，一个 block 覆盖的全部原始消息都可追溯，lineage 不会断。

**生产实测比例**（论文 fig1, n=116 sessions）：tier-1 占 resident space 的 **92%**，tier-2 **7%**，tier-3 **<1%**；但 tier-2+3 承载了 **37%** 的已代表历史。逐层再蒸馏 = "pagoda effect"，这是单 session 容量随长度**亚线性**增长的原因。

### 2.5 decompress：逐层恢复是真的吗？

**是，但不是状态机意义上的"tier 递减"，而是两个预先算好的视图。**

代理层 `collectBlockContent(state, block, messages, {full})`：

| 模式 | 行为 |
|---|---|
| `full: false`（**默认**） | 返回"**一层**"：遍历 `block.directBlockIds` 里仍 active 的子 block，把它们的 summary 展开成 `[Compressed conversation section] — <blockId>: <topic>\n<summary>`，再补上未被 nested 覆盖的**直接**消息原文。**不递归到底。** |
| `full: true` | 用 `block.effectiveMessageIds` 过滤 messages，格式化成 `[role]\n text` 用 `\n\n` 连接 → **全量原文** |

`state.blockContents[blockId]` 缓存了 `{full, one}` 两份，摘要生成时就已算好——decompress 只是查表，**零额外 LLM 成本**。

工具描述（与给模型的说明一致）：
> Restores previously compressed content. Use when you need exact details lost in compression. **By default restores one tier up.** Use full:true for all the way to original messages. Use toFile to write to file instead of inflating context.

参数：`blockId`（必填）、`toFile`（写文件而不塞回 context，保住 KV prefix）、`full`（递归到底）。

`markBlockRestoredInline(state, blockId)` 会给 block 打 `restoredInline: true` 并回填 `restoredStartRef/restoredEndRef`——**decompress 语义 = 重新激活 block 的来源 + 停用该 block**，不是"把 tier 从 2 降到 1"。

派生 session 的 block 不支持带区间恢复：
```
[decompress FAILED: range restore (startId/endId) of derived-parent blocks is not
 supported — decompress "bN" without range args (#1333)]
```

### 2.6 search_context：用什么检索？

**不是向量。没有 embedding。没有持久倒排索引。**

内核里其实有**四套**检索算法（`src/search/algorithms/`），`DEFAULT_ALGORITHM = "hybrid"`：

| 算法 | 行数 | 说明 | 自测 MRR / R@1 / R@3 |
|---|---|---|---|
| `substring` | 34 | 原始子串计数打分（topic 命中 +0.15 上限 0.45；summary +0.04 上限 0.2） | 0.797 / 0.792 / 0.792 |
| `bm25` | 55 | k1=1.2, b=0.75，**stemming + CJK bigram 分词** | 0.833 / 0.833 / 0.833 |
| `fuzzy` | 52 | 模糊匹配 | 0.795 / 0.708 / 0.875 |
| **`hybrid`** | 44 | `0.7 × BM25 + 0.3 × fuzzy`（各自 max 归一化） | **0.898 / 0.875 / 0.917** |

（基准：32 blocks / 48 条中英混合查询；注释称"weight ratio is robust: 0.6–0.8 for BM25 all score within 0.001 MRR"）

`tokenizer.ts`（124 行）值得照抄：
```ts
export const CJK = /[\u3400-\u9fff\uf900-\ufaff\u3040-\u30ff\uac00-\ud7af]/;
const cjkSegmenter = new Intl.Segmenter("zh", { granularity: "word" });
const LATIN_WORD = /[a-z][a-z0-9_]*[a-z0-9]|[a-z0-9]/g;  // 只收 length >= 2
// 全部 OOV 时回退重叠 bigram + 单字 → 保证"身份验证"能命中"身份验证流程"
```

**但代理层实际调的是 `substring`**（`searchBlocks(..., {algorithm:"substring", minScore:1e-9})`），不是 hybrid。`core.search(query, state)` 走的又是更老的 `scoreRelevance`。所以"默认 hybrid"是内核能力，不是线上行为。

角色加权：`DEFAULT_ROLE_WEIGHTS = { user: 1.5, assistant: 1.0, tool: 0.6, block: 1.0 }`。

搜索范围 = `blockDocs`（text = `${topic} ${summary}`，**含已 deactivate 的 block**）+ 可见消息。`limit` 默认 5（内核 `runSearch` 默认 10），`previewLength` 200，`minScore` 0.01。

**没有 block 命中时的提示文案**（值得抄）：
```
[No blocks matched "query" among N block(s)… Scope = compressed topics/summaries
(active + folded-away)… absence here ≠ never-discussed.]
```

第三方 benchmark（v0.1.202，1500 题）把 bili 与 full context 及 3 个 RAG arm 对打：ContextBench #1（Utilized F1 0.3097，成本 −58.3%）；LongMemEval-S 79.20% vs 最佳 RAG 62.60%；MemoryAgentBench 1.48M-token TTL 100% 存活（full context 199/199 失败）。**它靠结构而非向量检索赢。**

### 2.7 ACP tags 是谁注入的、干嘛用？

`<acp tokens="2.1K" type="tool:bash" time="2026-10-30 09:02:11">m00175</acp>`

- **谁注入**：kernel 的 `createRenderRefsNode`（每轮最后一个节点），由 `assignRefsNode` 铸号。**不是宿主、不是客户端、不是上游 API。**
- **干嘛用**：
  1. `compress` 调用里**指定要折叠哪段**（`m00150–m00220`）；
  2. 摘要里**引用锚点**（`User said (m00132): "…"`）；
  3. `decompress` 用它**精确恢复区间**。
- **字段**：`tokens` = 近似 token 数（相对参考）；`type` = 内容类型（`text` / `tool:bash` / `tool:Edit`…）；`time` = 该消息**首次出现**时铸的不可变本地时间戳（`compress.messageTimestamps`，默认 true，约 8 tokens/条）。
- **为什么不吃 prompt cache**：时间戳是**确定性渲染输入**（从存储值渲染，不是 wall clock），逐轮重渲染字节稳定。
- `ACP_RENDER_NONE=1` 可完全关掉注入（所有 wire 格式）。

### 2.8 保护规则（代码强制，不是 prompt 请求）

- `ALWAYS_PROTECTED_TOOLS = ["compress", "acp_rule"]`
- `NEVER_PRESERVE_RECENT_TOOLS = ["decompress", "search_context", "read", "bash"]`
- `preserveRecentMessages = 5`，`preserveRecentTokens = 5000`（kernel 硬下限，生产实测 raw zone 数十 K）
- **最后一条 user 消息**永不压
- **第一条 user 消息永久 pin**（`DESIGN.md` §8.1 称为 load-bearing 契约：否则 Anthropic 类 provider 会拒绝"没有 user 消息"的会话）。`prune.ts` 里 pin 检查在 covered-by-active-block 检查**之前**——所以压缩含首条 user 区间后，wire 上会同时出现 summary + 首条 user 原文。这是**故意**的。

---

## 3. 依赖

| 项 | 结论 |
|---|---|
| **Node 运行时** | 外壳需要，**`engines: {"node": ">=20"}` 是硬门槛**。内核 `acp-kernel` **零运行时依赖**（devDeps 只有 tsx/tsup/prettier/typescript/@types/node） |
| **SQLite** | **可选**。`node:sqlite`（Node ≥ 22.5）→ better-sqlite3 → 自下载 → 回退 JSON |
| **向量库** | **完全没有**。全包依赖只有 `mysql2`（MySQL 后端，懒加载）+ `zod`，optional: `jimp`（图像压缩） |
| **Python / Go 版本** | **没有**。`hermes-plugin/__init__.py` 只是 Python 胶水层（仅 stdlib），仍然 spawn Node proxy |
| **HTTP API** | **有，而且很干净**（见下） |
| **平台辅助二进制** | **无**。纯 JS/TS |

### 对外 HTTP API（这是路线 A 的核心依据）

**管理面（`/__bili/*`，loopback-only，**无任何鉴权**）：**
```
GET  /__bili/health          {"ok":true,"upstream":"https://api.anthropic.com","version":"0.1.213",…}
GET  /__bili/stats           每 session 的 requests/tokens/cacheHitPct/blocks…
GET  /__bili/overview        {"sessions":259,"requests":31271,"inputTokens":4.13e9,…}
GET  /__bili/memory          rss/heap/pool/breakdown（实测 idle RSS 113–121 MB）
GET  /__bili/sessions  /__bili/config  /__bili/status  /__bili/cache-report
POST /__bili/storage/gc  /__bili/storage/restore  /__bili/storage/backup
POST /__bili/replicas        检查点 fork
POST /__bili/shutdown
```

**工具面（这才是 App 需要的）：**
```
POST /__bili/plugin/tool
  headers: x-bili-plugin: <agent>
  body:    {"conversationId":"…","tool":"compress|decompress|search_context|acp_status","args":{…}}
  resp:    {"ok":true,"tool":"acp_status","conversationId":"…","outcome":"success","result":"<text>",…}
```
**实测通过**（我起的 instance 上跑通了 `acp_status`，返回完整上下文报告）。

```
POST /__bili/plugin/turn     增量 turn（anthropic wire shape；需 request.model）
POST /__bili/plugin/register 注册 agent + 上报能力
GET  /__bili/plugin/status|manifest|runtime-info|snapshot|render
POST /__bili/plugin/compact|fork|session-name|attribution
GET  /__bili/plugin/manifest 实测返回 {"protocolVersion":1,"proxy":"billion-context",
                                 "toolNames":["compress","decompress","search_context",
                                 "acp_status","acp_cache","acp_read_original_image"],…}
```

⚠️ **两条硬限制**：
1. `/__bili/plugin/turn` **不接受 OpenAI tool 形态**（`assistant.tool_calls` / `role:"tool"` / `tool_call_id` 会 400），只能 anthropic wire shape 或全量 resend。
2. **端口无鉴权**。README 原文："there is no authentication, so expose the port only on a trusted network"。容器镜像监听 `0.0.0.0:8787`。

---

## 4. License

**MIT + 一条附加条款**（`billion-context` 与 `acp-kernel` 的 LICENSE 完全同构，中英双语，57 行）。

```
MIT License
Copyright (c) 2026 ranxianglei

Additional Term — Attribution on Product Surfaces

若将本软件（含其内核 acp-kernel）用于任何终端用户可见 / 可交互的产品或服务
（商业或开源均算），必须在以下至少一处显著标注出处：
  1) 产品首页
  2) 产品文档
  3) About / Credits / Acknowledgements 页面
须说明使用了 billion-context（涉及时含 acp-kernel），并附链接
https://github.com/ranxianglei/billion-context
https://github.com/ranxianglei/acp-kernel

纯服务端 / 内嵌无用户界面的用途 → 随附文档声明即可。
```

**结论**：
- ✅ 可以改
- ✅ 可以商用
- ✅ 可以闭源分发（**不是 copyleft**，不传染）
- ⚠️ **唯一义务：署名**。虚拟伴侣 App 有 About 页，加一行 "Uses billion-context / acp-kernel" + 两个链接即可完全合规。

---

## 5. 移植到 Android：三条路线

### 前置事实（对 3GB RAM 手机很关键）

| 指标 | 实测值 | 对 3GB 手机的含义 |
|---|---|---|
| 包体积 | **54 MB**（dist 8.4 MB + node_modules 7.4 MB + 文档/论文） | Termux 装得下，但要占 54 MB |
| 常驻 RSS（idle） | **113–121 MB** | 吃掉 4% 内存，可接受但不算轻 |
| 本机 session 库大小 | **index.db = 1.3 GB / 259 session** | ⚠️ 平均 5 MB/session，长期陪伴 App 会长得很快 |
| Node 版本 | 本机 v24.22.1；engines `>=20` | Termux `pkg install nodejs-lts` 是 22.x，OK |
| arm64 兼容 | 纯 JS，无原生绑定（除非走 SQLite 自下载路径） | ✅ |

> ⚠️ README / CONFIGURATION.md 里 **grep 不到任何 termux / android / RSS 说明** → 安卓可行性**无官方背书**。

### 路线 A：不移植，跑在 Termux / PC，App 通过 HTTP 调

```
┌──────────────────────┐        HTTP         ┌────────────────────────┐
│  Companion App       │  POST /__bili/      │  bili proxy (Node 20+) │
│  (Kotlin/Compose)    │  plugin/tool        │  Termux on same phone  │
│                      │ ──────────────────► │  或 PC on LAN          │
│  - 自己持有 full     │                     │  - 持有 state/summary  │
│    history (Room)    │ ◄────────────────── │  - SQLite index.db     │
│  - 调 compress 前先把│   {ok, result}      │  - 22.5K growth gate   │
│    summary 算好传过去│                     │                        │
└──────────┬───────────┘                     └────────────────────────┘
           │ 直连
           ▼
      上游 LLM API
```

**关键点**：App **不需要**把 baseURL 指过去。它只要在"我准备压缩了"的时候，自己调一次 LLM 生成摘要（用 chat pack 的 prompt），然后把摘要 `POST /__bili/plugin/tool` 给 bili 记账。bili 只负责 state 和恢复。

**成本**：
- 代码：**300–500 行 Kotlin**（Retrofit/OkHttp 调用 + DTO + 错误处理 + 健康检查）
- 部署：Termux `pkg install nodejs-lts && npm i -g billion-context && bili --port 18787`
- 风险：
  - 🔴 **Android 后台进程回收**。Termux 必须开 foreground service + wake lock + 电池优化白名单，否则 proxy 随时被杀，App 的连接全部挂掉。这是**最致命**的问题——不是技术难度，是用户体验的持续熵。
  - 🟡 端口无鉴权。必须绑 `127.0.0.1`（默认就是），但 PC-on-LAN 方案下必须自己做一层 auth proxy 或仅限可信 Wi-Fi。
  - 🟡 两套 storage（App 的 Room + bili 的 index.db）会**双写同一份历史**，容易漂移。bili 的 `plugin lane` 有 "client-owned history" 模式（`biliMsgIds`），但默认 0% 灰度（`rollout.features.pluginLaneServerHistory`）。
  - 🟢 升级由 npm 管，App 不用发版。

**评价**：**能跑，但把一个每分钟都要用的陪伴 App 的命脉，挂在一个随时可能被系统杀掉的 Termux 进程上，是架构级的赌博。**

### 路线 B：只移植"压缩+摘要+检索"三段逻辑到 Kotlin

```
┌───────────────────────────────────────────────────────────┐
│                    Companion App (纯 Kotlin)              │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  AcpKernel (纯函数, 无 Android 依赖, 可单测)         │ │
│  │                                                      │ │
│  │  RefAssigner      mNNNNN 铸号 + stamp              │ │
│  │  BlockSync        消息↔block 投影                    │ │
│  │  BoundaryResolver 区间→索引 + 受保护 gap            │ │
│  │  Pruner           first-user pin + 预算不变量       │ │
│  │  Filter           可见性规则                         │ │
│  │  NudgeDecider     50K/22.5K/0.75/0.95 全套           │ │
│  │  Compressor       applyCompression + 校验           │ │
│  │  Decompressor     one-view / full-view              │ │
│  │  Searcher         substring + BM25 + hybrid         │ │
│  │  RenderRefs       <acp> tag 注入                    │ │
│  └────────────────────┬────────────────────────────────┘ │
│                       │ CompressionState (纯数据)         │
│  ┌────────────────────▼────────────────────────────────┐ │
│  │  Room / SQLite                                       │ │
│  │   messages(id, role, text, tokens, ts, blockId)      │ │
│  │   blocks(blockId, tier, topic, summary, active,      │ │
│  │          directMsgIds, effectiveMsgIds, directBlockIds)│
│  │   FTS5(messages)  ← 可选，替代手写 BM25             │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                           │
│  LLM client (直接调上游，摘要由同一个模型写)              │
└───────────────────────────────────────────────────────────┘
```

**要写多少代码？**（内核源码 14,306 行 TS，逐模块行数来自 sourcemap）

| 模块 | TS 行数 | Kotlin 估算 | 说明 |
|---|---|---|---|
| `compress.ts` | 2031 | ~1200 | 含 decideNudge + applyCompression。最大的单块 |
| `parse-compress-input.ts` | 889 | ~200 | Kotlin 有类型系统，大部分省掉 |
| `compress-tools.ts` | 807 | ~500 | 4 个工具的 schema + 分发 |
| `boundaries.ts` | 428 | ~350 | 区间→索引解析 |
| `prune.ts` | 394 | ~300 | 含 first-user pin |
| `recommend.ts` | 462 | ~350 | 推荐可压区间 |
| `decompress.ts` | 297 | ~250 | one/full 双视图 |
| `protected.ts` | 268 | ~200 | 保护规则 |
| `hide-consumed.ts` | 263 | ~200 | 隐藏压缩工具噪声 |
| `config.ts` + types | 358 | ~250 | data class |
| search（8 个文件合计） | 721 | ~600 | tokenizer 124 + bm25 55 + hybrid 44… |
| `sync/refs/render-refs/filter/pipeline` | ~600 | ~500 | 流水线骨架 |
| | | | |
| **合计（砍掉 crush 1832 / ccr 651 / image-compress 584 / cache-report 717 / absorb 358 / output-steering 321 / report 528）** | | **≈ 4,900 行** | 不含 UI/DI/网络层 |
| Room schema + DAO + 迁移 | | ~600 | |
| **总计** | | **≈ 5,500 行 Kotlin** | |

**检索用什么？** 两条路：
- **照抄内核**：`substring`(34) + `BM25`(55) + `hybrid`(44) + `tokenizer`(124) ≈ **260 行 Kotlin**。不用 FTS5，因为 block 数量级只有几十到几百，全量扫描 O(n×m) 完全够。而且内核注释的实测数据说明 hybrid 最好（MRR 0.898）。
- **用 FTS5**：省掉 tokenizer 和 BM25 的代码，但 CJK 分词要自己配 `unicode61` + `simple`，中文效果不如 `Intl.Segmenter` 等价物（Android 没有 `Intl.Segmenter`，要用 `android.icu.text.BreakIterator`）。

**推荐：照抄内核的 260 行方案**，因为可测试、行为确定、跨平台一致，而且 `BreakIterator` 与 `Intl.Segmenter` 语义接近。

**成本**：**4–6 周**（1 人全职），其中 60% 时间在做**测试对齐**——把内核的 550+ test 里挑出核心的 100–150 个移植成 JUnit，逐字节对齐 nudge 决策和边界解析。

**风险**：
- 🟡 一次投入大，但**产出是确定性的**（有可运行的参考实现 + 完整 `.d.ts` + DESIGN.md）。
- 🟡 Kotlin 没有 `Record<string,string>` 的灵活性，state 的序列化要小心（Room + kotlinx.serialization）。
- 🟢 零运行时依赖、零网络、可在 JVM 上单测、不占内存（内核本身只做字符串和数组操作）。
- 🟢 License 允许闭源，只需 About 页署名。

**评价**：**技术上完全可行，工作量真实但不虚。这是唯一能做出"产品级体验"的路线。**

### 路线 C：不移植代码，只借鉴分层摘要思想，自己写 200 行

**"200 行"是幻想。** 只做 T1 单层 + 关键词检索 + Room 持久化，最少也要：

| 部分 | 行数 |
|---|---|
| 数据模型（Message / Block / State）+ Room schema | 120 |
| mNNNNN 铸号 + 时间戳 | 40 |
| 阈值判定（23K growth / 75%） | 50 |
| `compress` 工具 schema + content 解析（`m00150–m00220 topic\n摘要`） | 90 |
| applyCompression（建 block、标记 effectiveMessageIds、从视图移除） | 100 |
| decompress（full 视图） | 60 |
| search_context（substring） | 50 |
| 渲染（拼 wire messages + `<acp>` tag） | 70 |
| **合计** | **≈ 580 行** |

**丢掉了什么**：
- ❌ T2/T3 分层 → context 只能无限增长（T1 summary 累积到一定量后就没有压缩空间了，这正是论文 §7 说的 pagoda effect）
- ❌ 保护规则（first-user pin / 最近区 / 工具保护）→ 会出现"模型忘了用户在说什么"的 bug
- ❌ 精确 lineage 恢复 → decompress 只能粗糙地按范围取
- ❌ 边界校验 → 模型给个错区间就崩
- ❌ nudge 的 model-decided side call、plan-aware re-rank、consume lineage…

**成本**：**3–5 天**能出个能跑的 demo；**2–3 周**能到"用户不抱怨记忆断片"的水平（实际上会一路补到接近路线 B 的 1500–2000 行）。

**风险**：
- 🟢 起步快，能立刻验证"陪伴 App 需不需要这套东西"
- 🔴 后期返工概率高（补 T2/T3 时数据结构要重做）
- 🔴 没有参考实现对齐，nudge 阈值靠猜

**评价**：**适合做 spike 验证价值，不适合做产品底座。** 如果非要做，建议直接按路线 B 的模块划分写，只是先实现其中 3 个模块——这样后续扩展不需要重写。

### 对比表

| | **A. Termux/PC 跑服务** | **B. 移植核心到 Kotlin** | **C. 借鉴思想自写简化版** |
|---|---|---|---|
| **形态** | App + 外部 Node 进程 | App 内纯 Kotlin | App 内纯 Kotlin |
| **代码量** | 300–500 行（仅 HTTP client） | **≈5,500 行**（core 4,900 + Room 600） | 580 行起（实际会涨到 1,500–2,000） |
| **工期** | 2–4 天 | **4–6 周** | 3–5 天（demo）/ 2–3 周（可用） |
| **运行时依赖** | Node 20+ / 54 MB / 120 MB RSS | **无** | **无** |
| **存储** | index.db（实测 5 MB/session） | Room/SQLite（可自控） | Room/SQLite |
| **致命风险** | 🔴 Android 后台杀进程 | 🟡 工作量 | 🔴 功能残缺，返工 |
| **隐私** | 🟡 历史多一份在 Termux FS | 🟢 全部在 App sandbox | 🟢 同 B |
| **可测试性** | 🔴 跨进程，难单测 | 🟢 纯函数，JVM 单测 | 🟢 |
| **升级** | npm 管，App 免发版 | 跟 App 版本走 | 同 B |
| **License** | 署名（含 About 页） | 署名（含 About 页） | **不需要**（只借思想，不复制代码） |
| **能否达到论文效果** | ✅ 完整 | ✅（若全搬） | ❌（T1 only，无 pagoda） |

### 我的推荐

**路线 B，但分期做。**

**理由**：
1. 内核是**纯函数 + 零依赖 + 完整类型定义 + DESIGN.md 契约**，这是移植的理想形态——没有平台绑定，没有黑箱，所有阈值都写在 `defaultConfig()` 里。
2. `chat` prompt pack 是**现成的陪伴场景 prompt**，可以直接复制进 App（它不涉及代码逻辑，纯文本）。
3. 检索只需 ~260 行，不需要向量库、不需要 embedding API——**这对离线/低成本的陪伴 App 是决定性的**。
4. 路线 A 的 Termux 方案在 3GB 手机上必死于后台进程管理，这不是技术判断，是 Android 的系统事实。
5. 路线 C 省下的 4 周，会在补 T2/T3 时加倍还回去，且数据结构要重做。

**分期建议**：
- **Phase 1（1.5–2 周，≈1,800 行）**：T1 压缩 + full-view decompress + substring 检索 + Room + `<acp>` tag 渲染 + 保护规则。这一期结束时 App 已经能"记住很久以前的事"。
- **Phase 2（1.5 周，≈1,500 行）**：T2 蒸馏 + `directBlockIds` lineage + one-view decompress + BM25/hybrid。
- **Phase 3（1 周，≈1,200 行）**：T3 + nudge 的 model-decided side call + 完整测试对齐。
- **Phase 4（可选）**：`acp_rule` 持久规则（`maxRules=50, maxRuleChars=300`）——对陪伴 App 意外地有用（"用户讨厌被叫宝贝"这种该永久记住的纠正）。

**必须做的合规动作**：About 页加
```
Uses billion-context and acp-kernel
https://github.com/ranxianglei/billion-context
https://github.com/ranxianglei/acp-kernel
```

---

## 附录 A：内核 API 精确签名（Kotlin 重写的对照表）

```ts
core.processTurn({ messages, state, config, tokenCount, contentStore? })
  → { messages, state, nudge?, contentStore }

core.applyCompression({ ranges: {startRef,endRef,summary,topic?}[], messages, state, config })
  → { state, result: { blocksCreated, tokensCompressed, errors } }

core.resolveBoundaries({ startRef, endRef, messages, state })
  → { startIndex, endIndex, protectedGaps: number[] }

core.decompress(blockId, state) → CompressionBlock | undefined
core.search(query, state)       → CompressionBlock[]
core.status(state, tokenCount, config) → StatusReport
core.retrieve(store, ref)       → ApplyRetrieveResult        // CCR
core.decideNudge(...)           → NudgeDecision | undefined
```

**Ports 只有两个**：`countTokens(text)`（内核自带词级 + CJK 实现）+ "每轮把 messages 传进来"。**没有 storage port**——持久化是宿主的事，这让 Android 端可以自由选 Room。

## 附录 B：默认配置全量（`defaultConfig(modelContextLimit)`）

```ts
tiers:            { enabled: true, tier2Trigger: 1000, tier3Trigger: 2000 }
nudge:            { maxContextLimitPct: 0.75, minContextLimitPct: 0.45,
                    frequency: 5, iterationThreshold: 15, force: "soft",
                    growthRatio: 0.05, growthFloor: 50000, growthCap: 50000,
                    minGrowthFloor: 20000, minGrowthRatio: 0.45,
                    emergencyThresholdPct: 0.95, tier2GrowthMultiplier: 1.5 }
promotionThreshold: 5
truncate:         { threshold: 0.95, terminalEscapeAfter: 3 }
compress:         { minCompressRange: 5000, maxSummaryLength: 20000, minSummaryLength: 50 }
preserveRecentMessages: 5
preserveRecentTokens:   5000
absorb:           { enabled: false, toolName: "absorb", minToolTokens: 4000 }
crush:            { enabled: false, minReduction: 0.1 }
imageCompression: { enabled: false, minTokens: 512, maxDimension: 1280, quality: 80, format: "webp" }
ccr:              { enabled: false, toolName: "acp_retrieve", minToolTokens: 4000, maxHeadChars: 96 }
```

## 附录 C：`acp_rule` — 值得单独抄的一个小机制

`src/rules.ts`，`maxRules = 50`，`maxRuleChars = 300`。
模型通过工具持久化"行为纠正 / 不变量 / 踩过的坑"，每轮注入 system prompt。规则 id 是 `rule1`、`rule2`… 单调递增，删了也不复用。

对陪伴 App 而言，这就是"**用户明确纠正过一次的东西，应该永久记住**"的机制——比塞进 summary 更可靠（summary 会被 T2/T3 再蒸馏掉）。

## 附录 D：参考文件索引（本机）

```
C:/Users/<you>/.dsh/profiles/desktop/node_modules/billion-context/
  ├── LICENSE                                   (MIT + Attribution, 57 行)
  ├── README.md                                 (77 KB，架构/部署/FAQ)
  ├── CONFIGURATION.md                          (401 KB，全部配置项)
  ├── DESIGN.md                                 ← acp-kernel 的（在 /tmp/acpk/package/）
  ├── dist/index.js                             (6.0 MB, 7824 行, proxy+CLI)
  ├── dist/agent/dsh-native.js                  (2.4 MB, 175 行, DSH 适配器)
  ├── dist/agent/dsh-native-client.js           (762 行)
  ├── dist/mcp.js                               (137 行, MCP stdio shell)
  ├── dsh.bundle.patch.yml                      (DSH bundle 注入点)
  ├── paper/*.md                                (论文 + 6 图 + 中英版)
  └── hermes-plugin/__init__.py                 (Python 胶水层)

/tmp/acpk/package/                              (npm pack acp-kernel@0.0.101)
  ├── DESIGN.md                                 (271 行，权威契约)
  ├── LICENSE                                   (与主包同构)
  ├── dist/index.js                             (8877 行)
  ├── dist/wire/index.js                        (1946 行)
  ├── dist/persist/index.js                     (540 行)
  └── dist/*.d.ts                               (每模块一份，完整类型)
```
