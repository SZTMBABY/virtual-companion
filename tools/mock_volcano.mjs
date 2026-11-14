// 火山豆包双向流式 TTS 的本地假服务器。
//
// 目的：在拿到真实密钥之前，先验证 app 侧手工拼的二进制帧头/事件号/时序是否自洽。
// 只用 Node 内置模块（本机 npm install 拿不到 ws），手写 RFC6455 握手与帧解析。
//
// 用法：
//   node tools/mock_volcano.mjs 8090
//   adb reverse tcp:8090 tcp:8090
//   App 设置页把 TTS 端点指向 ws://127.0.0.1:8090/  （仅 DEBUG 构建支持）
//
// 校验点：
//   1. 首帧必须是 Full-client + event=1(StartConnection)
//   2. 收到 ConnectionStarted(50) 之后才能发 StartSession(100)
//   3. 收到 SessionStarted(150) 之后才能发 TaskRequest(200)
//   4. TaskRequest 的 req_params.text 非空
//   5. audio_params.format 必须是 pcm，否则回错误帧

import { createServer } from 'node:http'
import { createHash } from 'node:crypto'

const PORT = Number(process.argv[2] || 8090)
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

const MSG_FULL_CLIENT = 0b0001
const MSG_FULL_SERVER = 0b1001
const MSG_ERROR = 0b1111

const EV = {
  1: 'StartConnection',
  2: 'FinishConnection',
  100: 'StartSession',
  101: 'CancelSession',
  102: 'FinishSession',
  200: 'TaskRequest',
  50: 'ConnectionStarted',
  51: 'ConnectionFailed',
  52: 'ConnectionFinished',
  150: 'SessionStarted',
  151: 'SessionCanceled',
  152: 'SessionFinished',
  153: 'SessionFailed',
  350: 'TTSSentenceStart',
  351: 'TTSSentenceEnd',
  352: 'TTSResponse',
}

function log(...a) {
  console.log(new Date().toISOString().slice(11, 23), ...a)
}

// ---- 帧构造函数侧（与服务端下发同构） -------------------------------------

function buildFrame(event, payloadBuf, msgType = MSG_FULL_SERVER, sessionId = null) {
  const parts = []
  const head = Buffer.alloc(4)
  head[0] = 0x11 // ver 1 | headerSize 1
  head[1] = ((msgType & 0x0f) << 4) | 0b0100 // type | flags(带 event)
  head[2] = 0x10 // serialization JSON | 无压缩
  head[3] = 0x00
  parts.push(head)

  const ev = Buffer.alloc(4)
  ev.writeInt32BE(event, 0)
  parts.push(ev)

  const sid = sessionId ? Buffer.from(sessionId, 'utf8') : Buffer.alloc(0)
  const sidLen = Buffer.alloc(4)
  sidLen.writeUInt32BE(sid.length, 0)
  parts.push(sidLen)
  if (sid.length) parts.push(sid)

  const len = Buffer.alloc(4)
  len.writeUInt32BE(payloadBuf.length, 0)
  parts.push(len)
  parts.push(payloadBuf)

  return Buffer.concat(parts)
}

function buildAudioFrame(payloadBuf, sessionId) {
  // 音频帧：type=0b1011，序列化 Raw(0)
  const parts = []
  const head = Buffer.alloc(4)
  head[0] = 0x11
  head[1] = ((0b1011 & 0x0f) << 4) | 0b0100
  head[2] = 0x00
  head[3] = 0x00
  parts.push(head)

  const ev = Buffer.alloc(4)
  ev.writeInt32BE(352, 0)
  parts.push(ev)

  const sid = Buffer.from(sessionId, 'utf8')
  const sidLen = Buffer.alloc(4)
  sidLen.writeUInt32BE(sid.length, 0)
  parts.push(sidLen, sid)

  const len = Buffer.alloc(4)
  len.writeUInt32BE(payloadBuf.length, 0)
  parts.push(len, payloadBuf)
  return Buffer.concat(parts)
}

// ---- 帧解析（客户端上行） -------------------------------------------------

function parseFrame(buf) {
  if (buf.length < 4) return null
  const headerSize = (buf[0] & 0x0f) * 4
  const msgType = (buf[1] & 0xf0) >> 4
  const flags = buf[1] & 0x0f
  const serialization = (buf[2] & 0xf0) >> 4
  let off = headerSize
  let event = null

  if (flags & 0b0100) {
    if (off + 4 > buf.length) return null
    event = buf.readInt32BE(off)
    off += 4
  }

  let sessionId = null
  if (off + 4 <= buf.length) {
    const maybe = buf.readUInt32BE(off)
    if (maybe <= 256 && off + 4 + maybe + 4 <= buf.length) {
      off += 4
      sessionId = maybe ? buf.slice(off, off + maybe).toString('utf8') : ''
      off += maybe
    }
  }

  let payload = Buffer.alloc(0)
  if (off + 4 <= buf.length) {
    const plen = buf.readUInt32BE(off)
    off += 4
    payload = buf.slice(off, Math.min(off + plen, buf.length))
  }

  return { msgType, event, sessionId, serialization, payload }
}

// ---- RFC6455 手写实现 -----------------------------------------------------

function wsSend(socket, buf) {
  const len = buf.length
  let header
  if (len < 126) {
    header = Buffer.from([0x82, len])
  } else if (len < 65536) {
    header = Buffer.alloc(4)
    header[0] = 0x82
    header[1] = 126
    header.writeUInt16BE(len, 2)
  } else {
    header = Buffer.alloc(10)
    header[0] = 0x82
    header[1] = 127
    header.writeBigUInt64BE(BigInt(len), 2)
  }
  socket.write(Buffer.concat([header, buf]))
}

function attachWs(socket) {
  let buf = Buffer.alloc(0)

  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk])

    // 解析所有完整的客户端帧（客户端帧必须带 mask）
    for (;;) {
      if (buf.length < 2) return
      const b0 = buf[0]
      const b1 = buf[1]
      const opcode = b0 & 0x0f
      const masked = (b1 & 0x80) !== 0
      let len = b1 & 0x7f
      let off = 2

      if (len === 126) {
        if (buf.length < 4) return
        len = buf.readUInt16BE(2)
        off = 4
      } else if (len === 127) {
        if (buf.length < 10) return
        len = Number(buf.readBigUInt64BE(2))
        off = 10
      }
      if (!masked) {
        log('!! 客户端帧未 mask，协议违规')
        socket.destroy()
        return
      }
      if (buf.length < off + 4 + len) return

      const mask = buf.slice(off, off + 4)
      off += 4
      const data = Buffer.from(buf.slice(off, off + len))
      for (let i = 0; i < data.length; i++) data[i] ^= mask[i % 4]
      buf = buf.slice(off + len)

      if (opcode === 0x8) {
        log('客户端关闭连接')
        socket.end()
        return
      }
      if (opcode === 0x9) {
        // ping → pong
        socket.write(Buffer.from([0x8a, 0x00]))
        continue
      }
      if (opcode === 0x1 || opcode === 0x2) {
        onFrame(data, socket)
      }
    }
  })
}

// ---- 会话状态 -------------------------------------------------------------

function onFrame(data, socket) {
  const f = parseFrame(data)
  if (!f) {
    log('!! 无法解析的帧，长度', data.length)
    return
  }
  const name = EV[f.event] || `event${f.event}`
  const text = f.payload.length <= 300 ? f.payload.toString('utf8') : `<${f.payload.length} bytes>`
  log(`← type=${f.msgType} event=${f.event}(${name}) sid=${f.sessionId} payload=${text}`)

  if (f.event === 1) {
    // StartConnection → 回 ConnectionStarted(50)
    if (!socket.__sid) socket.__sid = `conn-${Date.now()}`
    setTimeout(() => {
      log('→ event=50 ConnectionStarted')
      wsSend(socket, buildFrame(50, Buffer.from('{}', 'utf8')))
    }, 30)
    return
  }

  if (f.event === 100) {
    const j = safeJson(f.payload)
    const fmt = j?.req_params?.audio_params?.format
    const speaker = j?.req_params?.speaker
    log(`   StartSession speaker=${speaker} format=${fmt}`)
    if (fmt !== 'pcm') {
      log('!! audio_params.format 必须是 pcm，否则无法算口型')
    }
    const sid = `sess-${Date.now()}`
    socket.__sessionId = sid
    setTimeout(() => {
      log(`→ event=150 SessionStarted sid=${sid}`)
      wsSend(socket, buildFrame(150, Buffer.from('{}', 'utf8'), MSG_FULL_SERVER, sid))
    }, 30)
    return
  }

  if (f.event === 200) {
    const j = safeJson(f.payload)
    const say = j?.req_params?.text || ''
    log(`   TaskRequest text="${say}" 共 ${say.length} 字`)
    if (!say) {
      log('!! TaskRequest 文本为空')
      return
    }
    const sid = socket.__sessionId || 'sess-x'
    setTimeout(() => {
      log('→ event=350 TTSSentenceStart')
      wsSend(socket, buildFrame(350, Buffer.from('{}', 'utf8'), MSG_FULL_SERVER, sid))
    }, 20)

    // 造 1 秒 24k 单声道 PCM16 的正弦（带包络，便于观察口型）
    const sr = 24000
    const n = sr
    const pcm = Buffer.alloc(n * 2)
    for (let i = 0; i < n; i++) {
      const t = i / sr
      const env = Math.sin(Math.PI * Math.min(1, t * 2.2))
      const v = Math.round(32767 * 0.35 * env * Math.sin(2 * Math.PI * 140 * t))
      pcm.writeInt16LE(v, i * 2)
    }
    const chunk = 4800 // 100ms
    let sent = 0
    const timer = setInterval(() => {
      if (sent >= n * 2 || socket.destroyed) {
        clearInterval(timer)
        if (socket.destroyed) return
        setTimeout(() => {
          log('→ event=351 TTSSentenceEnd')
          wsSend(socket, buildFrame(351, Buffer.from('{}', 'utf8'), MSG_FULL_SERVER, sid))
        }, 20)
        setTimeout(() => {
          log('→ event=152 SessionFinished')
          wsSend(socket, buildFrame(152, Buffer.from('{}', 'utf8'), MSG_FULL_SERVER, sid))
        }, 60)
        return
      }
      const part = pcm.slice(sent, Math.min(sent + chunk, n * 2))
      sent += part.length
      wsSend(socket, buildAudioFrame(part, sid))
    }, 100)
    return
  }

  if (f.event === 102) {
    log('   FinishSession')
    return
  }

  if (f.event === 2) {
    log('   FinishConnection → 回 ConnectionFinished(52) 并关闭')
    wsSend(socket, buildFrame(52, Buffer.from('{}', 'utf8')))
    setTimeout(() => socket.end(), 50)
    return
  }
}

function safeJson(buf) {
  try {
    return JSON.parse(buf.toString('utf8'))
  } catch {
    return null
  }
}

// ---- HTTP 升级 ------------------------------------------------------------

const server = createServer((req, res) => {
  res.writeHead(426, { 'content-type': 'text/plain' })
  res.end('WebSocket only\n')
})

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key']
  const accept = createHash('sha1').update(key + GUID).digest('base64')
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  )
  log(`WS 升级 app_id=${req.headers['x-api-app-id']} resource=${req.headers['x-api-resource-id']} connect=${req.headers['x-api-connect-id']}`)
  attachWs(socket)
  socket.on('error', (e) => log('socket error', e.message))
})

server.listen(PORT, '127.0.0.1', () => log(`mock volcano listening ws://127.0.0.1:${PORT}/`))
