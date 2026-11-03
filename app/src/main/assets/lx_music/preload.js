'use strict'
/*
 * 洛雪音乐自定义源的运行环境：在脚本之前执行，搭出脚本看得到的 globalThis.lx 与定时器、console。
 *
 * 宿主（LxRuntime）先放一个 __lx_native__(key, action, data) 回调，再调 __lx_setup__(key, setup, rawScript)。
 * 这里把回调收进闭包、从全局删掉，脚本拿不到；宿主往回送消息走不可改写的 __lx_dispatch__，同样要带 key。
 * 两边只传字符串（JSON 与 base64），不在引擎之间传对象，免得引用计数出错。
 *
 * 接口与洛雪音乐桌面版、手机版一致：request、send、on、utils.crypto、utils.buffer、utils.zlib、
 * currentScriptInfo、version、env、EVENT_NAMES。缓冲区是 Uint8Array 的子类，toString 支持
 * utf8、hex、base64、binary，与桌面版的 Buffer 用法兼容。
 */
globalThis.__lx_setup__ = (key, setupJson, rawScript) => {
  delete globalThis.__lx_setup__
  const host = globalThis.__lx_native__
  delete globalThis.__lx_native__
  const setup = JSON.parse(setupJson)

  const call = (action, data) => host(key, action, data === undefined ? null : JSON.stringify(data))

  // ---- 字节与编码 ----------------------------------------------------------------

  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
  const B64_INDEX = new Int16Array(128).fill(-1)
  for (let i = 0; i < B64.length; i++) B64_INDEX[B64.charCodeAt(i)] = i
  B64_INDEX['-'.charCodeAt(0)] = 62
  B64_INDEX['_'.charCodeAt(0)] = 63

  const base64Encode = (bytes) => {
    let out = ''
    let i = 0
    for (; i + 2 < bytes.length; i += 3) {
      const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2]
      out += B64[n >> 18 & 63] + B64[n >> 12 & 63] + B64[n >> 6 & 63] + B64[n & 63]
    }
    const rest = bytes.length - i
    if (rest === 1) {
      const n = bytes[i] << 16
      out += B64[n >> 18 & 63] + B64[n >> 12 & 63] + '=='
    } else if (rest === 2) {
      const n = (bytes[i] << 16) | (bytes[i + 1] << 8)
      out += B64[n >> 18 & 63] + B64[n >> 12 & 63] + B64[n >> 6 & 63] + '='
    }
    return out
  }

  /** 与 Node 一样宽松：认 base64url，跳过空白与非法字符，补齐与否都行。 */
  const base64Decode = (text) => {
    const out = new LxBuffer(Math.floor(text.length * 3 / 4) + 3)
    let length = 0
    let bits = 0
    let value = 0
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i)
      if (code === 61) break
      const index = code < 128 ? B64_INDEX[code] : -1
      if (index < 0) continue
      value = (value << 6) | index
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out[length++] = (value >> bits) & 0xff
      }
    }
    return out.subarray(0, length)
  }

  const utf8Encode = (text) => {
    const out = []
    for (let i = 0; i < text.length; i++) {
      let c = text.charCodeAt(i)
      if (c >= 0xd800 && c <= 0xdfff) {
        const d = i + 1 < text.length ? text.charCodeAt(i + 1) : 0
        if (c <= 0xdbff && d >= 0xdc00 && d <= 0xdfff) {
          c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00)
          i++
        } else {
          c = 0xfffd
        }
      }
      if (c < 0x80) out.push(c)
      else if (c < 0x800) out.push(0xc0 | c >> 6, 0x80 | c & 63)
      else if (c < 0x10000) out.push(0xe0 | c >> 12, 0x80 | c >> 6 & 63, 0x80 | c & 63)
      else out.push(0xf0 | c >> 18, 0x80 | c >> 12 & 63, 0x80 | c >> 6 & 63, 0x80 | c & 63)
    }
    return LxBuffer.from(out)
  }

  const fromCodes = (codes) => {
    let out = ''
    for (let i = 0; i < codes.length; i += 8192) out += String.fromCharCode.apply(null, codes.slice(i, i + 8192))
    return out
  }

  const utf8Decode = (bytes) => {
    const codes = []
    for (let i = 0; i < bytes.length;) {
      const b = bytes[i]
      let c = 0xfffd
      let size = 1
      if (b < 0x80) {
        c = b
      } else if (b >= 0xc2 && b < 0xe0 && i + 1 < bytes.length && (bytes[i + 1] & 0xc0) === 0x80) {
        c = (b & 31) << 6 | bytes[i + 1] & 63
        size = 2
      } else if (b >= 0xe0 && b < 0xf0 && i + 2 < bytes.length && (bytes[i + 1] & 0xc0) === 0x80 && (bytes[i + 2] & 0xc0) === 0x80) {
        c = (b & 15) << 12 | (bytes[i + 1] & 63) << 6 | bytes[i + 2] & 63
        size = 3
        if (c < 0x800 || (c >= 0xd800 && c <= 0xdfff)) c = 0xfffd
      } else if (b >= 0xf0 && b < 0xf5 && i + 3 < bytes.length && (bytes[i + 1] & 0xc0) === 0x80 && (bytes[i + 2] & 0xc0) === 0x80 && (bytes[i + 3] & 0xc0) === 0x80) {
        c = (b & 7) << 18 | (bytes[i + 1] & 63) << 12 | (bytes[i + 2] & 63) << 6 | bytes[i + 3] & 63
        size = 4
        if (c < 0x10000 || c > 0x10ffff) c = 0xfffd
      }
      if (c > 0xffff) {
        c -= 0x10000
        codes.push(0xd800 + (c >> 10), 0xdc00 + (c & 1023))
      } else {
        codes.push(c)
      }
      i += size
    }
    return fromCodes(codes)
  }

  const hexEncode = (bytes) => {
    let out = ''
    for (let i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16)
    return out
  }

  const hexDecode = (text) => {
    const out = new LxBuffer(text.length >> 1)
    let length = 0
    for (let i = 0; i + 1 < text.length; i += 2) {
      const value = parseInt(text.substr(i, 2), 16)
      if (Number.isNaN(value)) break
      out[length++] = value
    }
    return out.subarray(0, length)
  }

  const latin1Encode = (text) => {
    const out = new LxBuffer(text.length)
    for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0xff
    return out
  }

  const latin1Decode = (bytes) => fromCodes(Array.from(bytes))

  const encodingOf = (encoding) => String(encoding || 'utf8').toLowerCase().replace('-', '')

  /** 桌面版脚本把缓冲区当 Node Buffer 用，最常见的是 toString('hex'/'base64')，这里补上这些。 */
  class LxBuffer extends Uint8Array {
    toString(encoding, start, end) {
      const bytes = (start !== undefined || end !== undefined) ? this.subarray(start || 0, end === undefined ? this.length : end) : this
      switch (encodingOf(encoding)) {
        case 'hex': return hexEncode(bytes)
        case 'base64': return base64Encode(bytes)
        case 'base64url': return base64Encode(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
        case 'binary':
        case 'latin1':
        case 'ascii': return latin1Decode(bytes)
        default: return utf8Decode(bytes)
      }
    }

    toJSON() {
      return { type: 'Buffer', data: Array.from(this) }
    }

    equals(other) {
      if (!other || other.length !== this.length) return false
      for (let i = 0; i < this.length; i++) if (this[i] !== other[i]) return false
      return true
    }

    static concat(list, totalLength) {
      const total = totalLength === undefined ? list.reduce((sum, item) => sum + item.length, 0) : totalLength
      const out = new LxBuffer(total)
      let offset = 0
      for (const item of list) {
        if (offset >= total) break
        const part = toBytes(item)
        out.set(part.subarray(0, total - offset), offset)
        offset += part.length
      }
      return out
    }

    static isBuffer(value) {
      return value instanceof LxBuffer
    }
  }

  const bufferFrom = (input, encoding) => {
    if (typeof input === 'string') {
      switch (encodingOf(encoding)) {
        case 'base64':
        case 'base64url': return base64Decode(input)
        case 'hex': return hexDecode(input)
        case 'binary':
        case 'latin1':
        case 'ascii': return latin1Encode(input)
        default: return utf8Encode(input)
      }
    }
    if (input instanceof ArrayBuffer) return new LxBuffer(input.slice(0))
    if (ArrayBuffer.isView(input)) return new LxBuffer(input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength))
    if (Array.isArray(input)) return LxBuffer.from(input.map(value => value & 0xff))
    if (input && input.type === 'Buffer' && Array.isArray(input.data)) return LxBuffer.from(input.data)
    if (typeof input === 'number') return new LxBuffer(input)
    throw new TypeError('Unsupported input type: ' + typeof input)
  }

  /** 字符串按 UTF-8 编码，其余按缓冲区读。 */
  const toBytes = (value) => {
    if (value instanceof Uint8Array) return value
    if (value == null) return new LxBuffer(0)
    return bufferFrom(typeof value === 'number' ? String(value) : value)
  }

  // ---- 宿主能力 ----------------------------------------------------------------

  const native = (action, data) => {
    const result = call(action, data)
    if (typeof result !== 'string') throw new Error(action + ' failed')
    const parsed = JSON.parse(result)
    if (parsed.error) throw new Error(parsed.error)
    return parsed.value
  }

  const utils = {
    crypto: {
      aesEncrypt(buffer, mode, key, iv) {
        const value = native('aes', {
          mode: String(mode),
          data: base64Encode(toBytes(buffer)),
          key: base64Encode(toBytes(key)),
          iv: iv == null ? '' : base64Encode(toBytes(iv)),
        })
        return base64Decode(value)
      },
      rsaEncrypt(buffer, key) {
        if (typeof key !== 'string') throw new Error('Invalid RSA key')
        return base64Decode(native('rsa', { data: base64Encode(toBytes(buffer)), key }))
      },
      randomBytes(size) {
        return base64Decode(native('random', { size: Number(size) || 0 }))
      },
      md5(str) {
        return native('md5', { data: base64Encode(toBytes(str)) })
      },
    },
    buffer: {
      from: bufferFrom,
      bufToString(buf, format) {
        const bytes = toBytes(buf)
        return (bytes instanceof LxBuffer ? bytes : new LxBuffer(bytes.buffer, bytes.byteOffset, bytes.byteLength)).toString(format)
      },
    },
    zlib: {
      inflate(buf) {
        return new Promise((resolve, reject) => {
          try {
            resolve(base64Decode(native('inflate', { data: base64Encode(toBytes(buf)) })))
          } catch (error) {
            reject(error)
          }
        })
      },
      deflate(buf) {
        return new Promise((resolve, reject) => {
          try {
            resolve(base64Decode(native('deflate', { data: base64Encode(toBytes(buf)) })))
          } catch (error) {
            reject(error)
          }
        })
      },
    },
  }

  // ---- console 与定时器 ----------------------------------------------------------------

  const describe = (value) => {
    if (typeof value === 'string') return value
    if (value instanceof Error) return value.stack ? value.message + '\n' + value.stack : String(value.message || value)
    if (value instanceof Uint8Array) return '<Buffer ' + hexEncode(value.subarray(0, 64)) + (value.length > 64 ? ' ...' : '') + '>'
    if (typeof value === 'function') return '[Function ' + (value.name || 'anonymous') + ']'
    try {
      const text = JSON.stringify(value)
      return text === undefined ? String(value) : text
    } catch {
      return String(value)
    }
  }
  const log = (level) => (...args) => {
    let text = args.map(describe).join(' ')
    if (text.length > 4096) text = text.substring(0, 4096) + '...'
    call('log', { level, text })
  }
  globalThis.console = {
    log: log('log'),
    info: log('info'),
    warn: log('warn'),
    error: log('error'),
    debug: log('debug'),
    trace: log('debug'),
    group() {},
    groupEnd() {},
    time() {},
    timeEnd() {},
  }

  const timers = new Map()
  let timerSeq = 0
  const addTimer = (callback, delay, params, repeat) => {
    if (typeof callback !== 'function') throw new TypeError('callback must be a function')
    const id = ++timerSeq
    const wait = Math.max(0, Math.min(Number(delay) || 0, 2147483647))
    timers.set(id, { callback, params, repeat, wait })
    call('timer', { id, delay: wait })
    return id
  }
  const fireTimer = (id) => {
    const timer = timers.get(id)
    if (!timer) return
    if (timer.repeat) call('timer', { id, delay: Math.max(timer.wait, 1) })
    else timers.delete(id)
    try {
      timer.callback(...timer.params)
    } catch (error) {
      console.error('Timer callback error:', error)
    }
  }
  globalThis.setTimeout = (callback, delay = 0, ...params) => addTimer(callback, delay, params, false)
  globalThis.setInterval = (callback, delay = 0, ...params) => addTimer(callback, delay, params, true)
  globalThis.setImmediate = (callback, ...params) => addTimer(callback, 0, params, false)
  globalThis.clearTimeout = (id) => { timers.delete(id) }
  globalThis.clearInterval = globalThis.clearTimeout
  globalThis.clearImmediate = globalThis.clearTimeout
  if (typeof globalThis.queueMicrotask !== 'function') globalThis.queueMicrotask = (callback) => { Promise.resolve().then(callback) }
  if (typeof globalThis.atob !== 'function') globalThis.atob = (text) => latin1Decode(base64Decode(String(text)))
  if (typeof globalThis.btoa !== 'function') globalThis.btoa = (text) => base64Encode(latin1Encode(String(text)))
  if (typeof globalThis.TextEncoder !== 'function') {
    globalThis.TextEncoder = class TextEncoder {
      get encoding() { return 'utf-8' }
      encode(text = '') { return new Uint8Array(utf8Encode(String(text))) }
    }
  }
  if (typeof globalThis.TextDecoder !== 'function') {
    globalThis.TextDecoder = class TextDecoder {
      get encoding() { return 'utf-8' }
      decode(bytes) { return bytes == null ? '' : utf8Decode(bytes instanceof ArrayBuffer ? new Uint8Array(bytes) : bytes) }
    }
  }

  // ---- 网络请求 ----------------------------------------------------------------

  const requests = new Map()
  let requestSeq = 0

  const plainObject = (value) => value != null && typeof value === 'object' && !(value instanceof Uint8Array) && !(value instanceof ArrayBuffer)

  const stringHeaders = (headers) => {
    const out = {}
    if (!plainObject(headers)) return out
    for (const [name, value] of Object.entries(headers)) {
      if (value == null) continue
      out[name] = Array.isArray(value) ? value.join(', ') : String(value)
    }
    return out
  }

  const stringFields = (fields) => {
    const out = {}
    for (const [name, value] of Object.entries(fields)) if (value != null) out[name] = typeof value === 'object' ? JSON.stringify(value) : String(value)
    return out
  }

  /** 请求体：字符串原样，缓冲区按字节，对象按 JSON（或请求头声明的表单）；form、formData 分别是表单与 multipart。 */
  const requestBody = ({ body, form, formData }) => {
    if (body != null) {
      if (typeof body === 'string') return { kind: 'text', value: body }
      if (body instanceof Uint8Array || body instanceof ArrayBuffer) return { kind: 'bytes', value: base64Encode(toBytes(body)) }
      if (typeof body === 'object') return { kind: 'json', value: JSON.stringify(body), fields: stringFields(body) }
      return { kind: 'text', value: String(body) }
    }
    if (plainObject(form)) return { kind: 'form', fields: stringFields(form) }
    if (typeof form === 'string') return { kind: 'form-text', value: form }
    if (plainObject(formData)) return { kind: 'multipart', fields: stringFields(formData) }
    return null
  }

  const request = (url, options, callback) => {
    if (typeof options === 'function') {
      callback = options
      options = {}
    }
    options = options || {}
    if (typeof callback !== 'function') callback = () => {}
    const id = ++requestSeq
    requests.set(id, callback)
    const timeout = Number(options.timeout)
    call('request', {
      id,
      url: String(url),
      method: String(options.method || 'get'),
      timeout: timeout > 0 ? Math.min(timeout, 60000) : 0,
      headers: stringHeaders(options.headers),
      body: requestBody(options),
      binary: options.binary === true,
    })
    return () => {
      if (requests.delete(id)) call('cancelRequest', { id })
    }
  }

  const finishRequest = (data) => {
    const callback = requests.get(data.id)
    if (!callback) return
    requests.delete(data.id)
    let error = null
    let response = null
    let body = null
    if (data.error) {
      error = new Error(data.error)
    } else {
      const raw = base64Decode(data.body || '')
      body = raw
      if (!data.binary) {
        body = utf8Decode(raw)
        try {
          body = JSON.parse(body)
        } catch {}
      }
      response = {
        statusCode: data.status,
        statusMessage: data.message || '',
        headers: data.headers || {},
        url: data.url,
        bytes: raw.length,
        raw,
        body,
      }
    }
    try {
      callback.call(lx, error, response, body)
    } catch (err) {
      console.error('Request callback error:', err)
    }
  }

  // ---- 事件 ----------------------------------------------------------------

  const EVENT_NAMES = Object.freeze({ request: 'request', inited: 'inited', updateAlert: 'updateAlert' })
  const ACTIONS = ['musicUrl', 'lyric', 'pic']
  const handlers = { request: null }
  let inited = false
  let updateAlerted = false

  const isHttpUrl = (value) => typeof value === 'string' && value.length <= 4096 && /^https?:\/\//i.test(value)

  const handleInit = (info) => {
    const sources = {}
    if (plainObject(info) && plainObject(info.sources)) {
      for (const [code, source] of Object.entries(info.sources)) {
        if (!/^[a-z0-9_]{1,16}$/.test(code) || !plainObject(source)) continue
        if (source.type != null && source.type !== 'music') continue
        const actions = Array.isArray(source.actions) ? source.actions.filter(action => ACTIONS.includes(action)) : ['musicUrl']
        const qualitys = Array.isArray(source.qualitys) ? source.qualitys.filter(q => typeof q === 'string' && /^[a-z0-9_]{1,24}$/i.test(q)) : []
        sources[code] = { name: typeof source.name === 'string' ? source.name.substring(0, 64) : '', actions, qualitys }
      }
    }
    if (!Object.keys(sources).length) {
      call('init', { status: false, message: 'No source declared' })
      return
    }
    call('init', { status: true, sources, openDevTools: !!(info && info.openDevTools) })
  }

  const verifyLyric = (info) => {
    if (typeof info === 'string') info = { lyric: info }
    if (!plainObject(info) || typeof info.lyric !== 'string') throw new Error('Invalid lyric')
    const text = (value) => typeof value === 'string' ? value : null
    return { lyric: info.lyric, tlyric: text(info.tlyric), rlyric: text(info.rlyric), lxlyric: text(info.lxlyric) }
  }

  const handleRequest = (data) => {
    const reply = (ok, payload) => call('response', ok ? { id: data.id, ok: true, data: payload } : { id: data.id, ok: false, message: String(payload) })
    if (typeof handlers.request !== 'function') {
      reply(false, 'Request event is not defined')
      return
    }
    let pending
    try {
      pending = Promise.resolve(handlers.request.call(lx, { source: data.source, action: data.action, info: data.info }))
    } catch (error) {
      reply(false, (error && error.message) || error)
      return
    }
    pending.then(result => {
      switch (data.action) {
        case 'musicUrl':
          // 本应用的扩展：{ url, ekey, format }，ekey 是加密音频的密钥，format 是解密后的容器。见 lx.voyage。
          if (plainObject(result) && isHttpUrl(result.url)) {
            const text = (value) => typeof value === 'string' && value.length <= 4096 ? value : null
            reply(true, { url: result.url, ekey: text(result.ekey), format: text(result.format) })
            break
          }
          if (!isHttpUrl(result)) throw new Error('Invalid url: ' + describe(result).substring(0, 200))
          reply(true, result)
          break
        case 'pic':
          if (!isHttpUrl(result)) throw new Error('Invalid url: ' + describe(result).substring(0, 200))
          reply(true, result)
          break
        case 'lyric':
          reply(true, verifyLyric(result))
          break
        default:
          reply(true, result)
      }
    }).catch(error => {
      reply(false, (error && error.message) || error || 'failed')
    })
  }

  const send = (eventName, data) => new Promise((resolve, reject) => {
    switch (eventName) {
      case EVENT_NAMES.inited:
        if (inited) return reject(new Error('Script is inited'))
        inited = true
        handleInit(data)
        resolve()
        break
      case EVENT_NAMES.updateAlert:
        if (updateAlerted) return reject(new Error('The update alert can only be called once.'))
        if (!plainObject(data) || typeof data.log !== 'string' || !data.log) return reject(new Error('log is required.'))
        updateAlerted = true
        call('updateAlert', {
          log: data.log.length > 1024 ? data.log.substring(0, 1024) + '...' : data.log,
          updateUrl: isHttpUrl(data.updateUrl) ? data.updateUrl : null,
        })
        resolve()
        break
      default:
        reject(new Error('The event is not supported: ' + eventName))
    }
  })

  const on = (eventName, handler) => {
    if (eventName !== EVENT_NAMES.request) return Promise.reject(new Error('The event is not supported: ' + eventName))
    handlers.request = handler
    return Promise.resolve()
  }

  const info = setup.info || {}
  const lx = {
    EVENT_NAMES,
    request,
    send,
    on,
    utils,
    currentScriptInfo: {
      name: info.name || '',
      description: info.description || '',
      version: info.version || '',
      author: info.author || '',
      homepage: info.homepage || '',
      rawScript,
    },
    version: setup.version,
    env: setup.env,
    // 本应用的扩展能力，脚本据此判断能否返回扩展格式；别的洛雪宿主没有这个字段。
    voyage: { version: 1, musicUrlObject: true, sealed: !!setup.sealed },
  }
  const freeze = (value) => {
    if (value == null || typeof value !== 'object' || Object.isFrozen(value)) return
    Object.freeze(value)
    for (const child of Object.values(value)) freeze(child)
  }
  freeze(lx)
  Object.defineProperty(globalThis, 'lx', { value: lx, enumerable: true, configurable: false, writable: false })

  Object.defineProperty(globalThis, '__lx_dispatch__', {
    enumerable: false,
    configurable: false,
    writable: false,
    value: (callerKey, action, payload) => {
      if (callerKey !== key) return
      const data = payload == null ? null : JSON.parse(payload)
      switch (action) {
        case 'timer': fireTimer(data); break
        case 'response': finishRequest(data); break
        case 'request': handleRequest(data); break
        case 'failed':
          // 脚本运行出错：没发过 inited 的不再等它。
          inited = true
          break
      }
    },
  })
}
