// 本地 OpenAI 兼容 SSE 假服务器 —— 只用于验证 p2 的流式链路，不参与交付。
//
// 用法：node tools/mock_openai.mjs [port]
// 然后 adb reverse tcp:8080 tcp:8080，App 里 Base URL 填 http://127.0.0.1:8080
//
// 它会按 OpenAI 的 SSE 格式吐 delta，内容是一行 JSON（和真实 prompt 要求一致），
// 并且故意分成很多小片段发送，用来验证 SayExtractor 的半截 JSON 容错。

import http from 'node:http'

const port = Number(process.argv[2] || 8080)

const REPLY = '{"say":"主人终于来啦，我一直在等你呢。今天想聊点什么？","emotion":"happy","motion":"nod"}'

const server = http.createServer((req, res) => {
  // 模型列表（验证设置页「拉取模型列表」按钮）
  if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
    const auth = req.headers['authorization'] || ''
    console.log('[mock] GET %s auth=%s', req.url, auth ? 'yes' : 'no')
    if (!auth.startsWith('Bearer ')) {
      res.writeHead(401, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'missing api key' } }))
      return
    }
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({
      object: 'list',
      data: [
        { id: 'deepseek-chat', object: 'model' },
        { id: 'deepseek-reasoner', object: 'model' },
        { id: 'mock-tiny', object: 'model' },
      ],
    }))
    return
  }

  if (req.method !== 'POST') {
    res.writeHead(405).end()
    return
  }
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    console.log('[mock] %s %s bytes=%d', req.method, req.url, body.length)
    try {
      const j = JSON.parse(body)
      console.log('[mock] model=%s stream=%s messages=%d', j.model, j.stream, j.messages?.length ?? 0)
      console.log('[mock] last user: %j', j.messages?.at(-1)?.content)
      // 逐条打印角色与开头，用来验证「记忆片段是否真的进了 system」
      for (const m of j.messages ?? []) {
        const c = String(m.content ?? '').replace(/\n/g, '⏎')
        console.log('[mock]   %s(%d): %s', m.role, c.length, c.slice(0, 120))
      }
    } catch (e) {
      console.log('[mock] bad json')
    }

    // 鉴权检查：故意保留，用来验证客户端确实发了 Bearer
    const auth = req.headers['authorization'] || ''
    if (!auth.startsWith('Bearer ')) {
      res.writeHead(401, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'missing api key' } }))
      return
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })

    // 按 6 个字符一片切开，刻意切在 JSON 字符串中间，验证半截解析
    const chunks = []
    for (let i = 0; i < REPLY.length; i += 6) chunks.push(REPLY.slice(i, i + 6))

    let i = 0
    const timer = setInterval(() => {
      if (i >= chunks.length) {
        clearInterval(timer)
        res.write('data: [DONE]\n\n')
        res.end()
        console.log('[mock] done, %d chunks', chunks.length)
        return
      }
      const payload = {
        id: 'chatcmpl-mock',
        object: 'chat.completion.chunk',
        model: 'mock',
        choices: [{ index: 0, delta: { content: chunks[i] }, finish_reason: null }],
      }
      res.write('data: ' + JSON.stringify(payload) + '\n\n')
      i++
    }, 120)

    // 必须挂在 res 上，不能挂 req：Node 16+ 里 IncomingMessage 的 'close' 在
    // 请求体读完时就触发，会把定时器立刻清掉，一个 chunk 都发不出去。
    res.on('close', () => clearInterval(timer))
  })
})

server.listen(port, '127.0.0.1', () => {
  console.log(`[mock] listening on http://127.0.0.1:${port}`)
  console.log('[mock] POST /v1/chat/completions')
})
