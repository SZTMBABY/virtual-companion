/**
 * VC Memory — billion-context 内核的宿主适配层（唯一接缝）。
 *
 * 只做数据搬运，不含任何压缩逻辑：判断该不该压、摘要写多少、
 * 分层蒸馏、线上投影、检索，全部由内核负责。
 *
 * 约定：
 *   原始消息永远传全量（raw），内核只决定「哪些不出现在线上」。
 *   所以这里维护 raw 列表 + state，wire 每次由 prune 投影出来。
 */
import * as K from './index.js';

let core = null;
let config = null;
let state = null;
let stateJson = '';
let limit = 32768;

/**
 * 手机场景覆盖值。
 * 内核默认 growthFloor=50000（token 增长）——伴侣对话跑一天也到不了，
 * 等于装了不压缩。实测把窗口当成「工作集」（不是模型真实 1M 窗口），
 * 配 growthFloor 150 / maxContextLimitPct 0.5 才能稳定在 30% 上下。
 */
const MOBILE = {
  // 只收缩「绝对门槛」，百分比一律保持内核默认（0.75 / 0.45 / 0.95）。
  // 理由：growthFloor 默认 50000 token 是给 1M 窗口设计的；伴侣对话一整天也就 2-4K token，
  // 用默认值永远不触发（实测 30 轮 inject 恒为 false）。这些绝对量必须按工作集缩放。
  nudge: {
    growthFloor: 300,
    growthCap: 600,
    minGrowthFloor: 180,
    minGrowthRatio: 0.2,
  },
  compress: { minCompressRange: 250, minSummaryLength: 40, maxSummaryLength: 3000 },
  preserveRecentMessages: 4,
  preserveRecentTokens: 500,
  tiers: { enabled: true, tier2Trigger: 4, tier3Trigger: 8 },
};

function ensure(lim) {
  if (!core) core = K.createCore();
  if (!state) state = K.createInitialState();
  // 窗口变了必须重建 config：内核的触发门是按 limit 的百分比 + 绝对增长量算的，
  // 沿用旧 config 会让「把窗口调大」变成「永远不压缩」。
  var next = (lim && lim > 0) ? lim : limit;
  if (!config || next !== limit) {
    limit = next;
    config = K.defaultConfig(limit, MOBILE);
  }
}

/** 宿主消息 → 内核 CoreMessage。字段名必须是 text + contentType（用 content 会静默 0 token）。 */
function toCore(msgs) {
  const out = [];
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i];
    if (!m) continue;
    const role = m.role === 'system' ? 'system' : (m.role === 'assistant' ? 'assistant' : 'user');
    out.push({
      id: String(m.id),
      role: role,
      contentType: 'text',
      text: m.text == null ? '' : String(m.text),
    });
  }
  return out;
}

function toWire(msgs) {
  const out = [];
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i];
    if (!m) continue;
    out.push({ id: m.id, role: m.role, text: m.text == null ? '' : String(m.text) });
  }
  return out;
}

function countAll(msgs) {
  let n = 0;
  for (let i = 0; i < msgs.length; i++) n += K.defaultCountTokens(msgs[i].text || '');
  return n;
}

function persist(nextState) {
  state = nextState;
  stateJson = JSON.stringify(state);
  return stateJson;
}

/** 每轮开始：跑内核流水线，拿到「该不该压」和「线上投影」。 */
export function turn(messagesJson, modelContextLimit) {
  try {
    ensure(modelContextLimit);
    const messages = toCore(typeof messagesJson === 'string' ? JSON.parse(messagesJson) : messagesJson);
    const tokens = countAll(messages);
    // renderTags:'none' —— 不往正文里插 <acp> 标签（手机 UI 上会看到乱码），
    // ref 由内核无条件分配，我们直接读 state.messageRefs。
    const r = core.processTurn({
      messages: messages,
      state: state,
      config: config,
      tokenCount: tokens,
      renderTags: 'none',
    });
    persist(r.state);
    const n = r.nudge || {};
    // wireTokens 必须用和 tokens 同一把尺子（内核 defaultCountTokens）算，
    // 否则 Kotlin 侧拿到的是两套口径的比值，会算出 >100% 的假压缩率。
    const wireTokens = core.countTokens ? core.countTokens(r.messages) : (function () {
      var t = 0; for (var i = 0; i < r.messages.length; i++) t += countAll([r.messages[i]]); return t;
    })();
    return JSON.stringify({
      ok: true,
      tokens: tokens,
      wireTokens: wireTokens,
      state: stateJson,
      wire: toWire(r.messages),
      nudge: {
        shouldInject: !!n.shouldInject,
        reason: n.reason || '',
        contextUsage: n.contextUsage || 0,
        tier: n.tier || 1,
        ranges: (n.compressibleRanges || []).map(function (x) {
          return { startRef: x.startRef, endRef: x.endRef, tokens: x.tokens, count: x.count };
        }),
      },
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

/** 模型写好摘要后，交给内核落块。 */
export function apply(rangesJson, messagesJson) {
  try {
    ensure();
    const ranges = typeof rangesJson === 'string' ? JSON.parse(rangesJson) : rangesJson;
    const messages = toCore(typeof messagesJson === 'string' ? JSON.parse(messagesJson) : messagesJson);
    const r = core.applyCompression({
      ranges: ranges,
      messages: messages,
      state: state,
      config: config,
    });
    persist(r.state);
    const wire = K.prune(messages, state, { injectSummaries: true });
    var wTok = 0; for (var j = 0; j < wire.length; j++) wTok += countAll([wire[j]]);
    return JSON.stringify({
      ok: true,
      state: stateJson,
      wire: toWire(wire),
      wireTokens: wTok,
      created: (r.result && r.result.blocksCreated) || 0,
      errors: (r.result && r.result.errors) || [],
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

/** 只投影，不跑流水线（启动恢复时用）。 */
export function project(messagesJson) {
  try {
    ensure();
    const messages = toCore(typeof messagesJson === 'string' ? JSON.parse(messagesJson) : messagesJson);
    const wire = K.prune(messages, state, { injectSummaries: true });
    return JSON.stringify({ ok: true, wire: toWire(wire), state: stateJson });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

/** 检索旧记忆（内核自带混合检索：substring + fuzzy + BM25）。 */
export function search(query) {
  try {
    ensure();
    const docs = K.blockDocs(state);
    const hits = K.searchBlocks(docs, String(query || ''));
    return JSON.stringify({
      ok: true,
      hits: hits.map(function (h) {
        return { blockId: h.blockId, title: h.title || '', preview: h.preview || '', score: h.score };
      }),
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

/** 摘要指令原文（内核自带，不手抄）。发给上层模型生成摘要。 */
export function compressPrompt() {
  try {
    ensure();
    const base = String(K.COMPRESS_PHILOSOPHY || '');
    const rules = String(K.HOW_TO_COMPRESS_RULES || '');
    const lang = String(K.LANGUAGE_PRESERVATION_RULE || '');
    return JSON.stringify({
      ok: true,
      philosophy: base,
      rules: rules,
      language: lang,
      maxSummaryChars: (config.compress && config.compress.maxSummaryLength) || 3000,
      minSummaryChars: (config.compress && config.compress.minSummaryLength) || 50,
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

export function status() {
  try {
    ensure();
    const s = core.status(state, 0, config);
    return JSON.stringify({ ok: true, status: s });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

export function exportState() { return stateJson || ''; }

export function importState(json) {
  try {
    state = json ? JSON.parse(json) : K.createInitialState();
    stateJson = JSON.stringify(state);
    ensure();
    return JSON.stringify({ ok: true, blocks: (state.blocks || []).length });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
  }
}

export function info() {
  return JSON.stringify({
    exports: Object.keys(K).length,
    limit: limit,
    defaults: config,
  });
}

const API = {
  turn, apply, project, search, compressPrompt, status,
  exportState, importState, info,
};
if (typeof window !== 'undefined') {
  window.VCMemory = API;
  if (window.NativeBridge && window.NativeBridge.onLog) {
    window.NativeBridge.onLog('[VCMemory] ready exports=' + Object.keys(K).length);
  }
}
export default API;
