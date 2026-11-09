#!/usr/bin/env node
// M0 探针：网易云 weapi 加密 + 扫码登录 + song/url/v1 批量验证
// 零依赖，只用 Node 内置模块。用法见 `help` 或 README.md。

import crypto from 'node:crypto'
import https from 'node:https'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = import.meta.dirname
const OUT = path.join(ROOT, 'out')
const COOKIE_FILE = path.join(ROOT, 'cookie.json')
const BASE = 'https://music.163.com'
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
const REQ_TIMEOUT = 15000
const POLL_MS = 800
const SONGS = ['2610618711', '2123113332', '2610618710']
const LEVELS = ['standard', 'higher', 'exhigh', 'lossless']
const QR_STATUS = new Map([
  [800, '二维码已过期'],
  [801, '等待扫码'],
  [802, '已扫码，等待手机确认'],
  [803, '登录成功'],
])
const qrStatusText = (code) => QR_STATUS.get(Number(code)) || `未知状态(${code ?? '-'})`

// weapi 固定参数（公开协议）
const IV = '0102030405060708'
const PRESET_KEY = '0CoJUm6Qyw8W8jud'
const PUB_KEY = '010001'
const MODULUS = '00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7'
const B62 = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
const COOKIE_ATTRS = new Set(['path', 'domain', 'expires', 'max-age', 'httponly', 'secure', 'samesite'])

// 运行期 cookie 罐；NMTID 每次启动随机生成
const jar = new Map([['NMTID', crypto.randomBytes(16).toString('hex')]])
let realIP = ''

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------- weapi 加密 ----------

function aesEncrypt(text, key) {
  const cipher = crypto.createCipheriv('aes-128-cbc', Buffer.from(key), Buffer.from(IV))
  return Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]).toString('base64')
}

// RSA_NO_PADDING：把倒序后的随机 key 当大整数做模幂，输出 256 位 hex
function rsaEncrypt(text) {
  const reversed = [...text].reverse().join('')
  const m = BigInt('0x' + MODULUS)
  const e = BigInt('0x' + PUB_KEY)
  const c = BigInt('0x' + Buffer.from(reversed, 'utf8').toString('hex'))
  return (c ** e % m).toString(16).padStart(256, '0')
}

function weapi(data) {
  // 16 位随机 key，再做一次 AES
  const secret = Array.from(crypto.randomBytes(16), (b) => B62[b % B62.length]).join('')
  const text = JSON.stringify(data)
  return {
    params: aesEncrypt(aesEncrypt(text, PRESET_KEY), secret),
    encSecKey: rsaEncrypt(secret),
  }
}

// ---------- HTTP / cookie ----------

function setCookiePair(pair) {
  const i = pair.indexOf('=')
  if (i <= 0) return
  const k = pair.slice(0, i).trim()
  const v = pair.slice(i + 1).trim()
  if (k && !COOKIE_ATTRS.has(k.toLowerCase())) jar.set(k, v)
}

function absorbSetCookie(list) {
  if (typeof list === 'string') list = [list]
  for (const line of list || []) setCookiePair(String(line).split(';')[0])
}

function absorbCookieText(text) {
  if (typeof text !== 'string' || !text) return
  for (const part of text.split(';')) setCookiePair(part)
}

// 803 的登录态可能直接是 cookie 串，也可能是对象/数组
function absorbCookieValue(value) {
  if (!value) return
  if (typeof value === 'string') {
    absorbCookieText(value)
    return
  }
  if (Array.isArray(value)) {
    for (const item of value) absorbCookieValue(item)
    return
  }
  if (typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      if (typeof v === 'string' && v && !COOKIE_ATTRS.has(k.toLowerCase())) jar.set(k, v)
    }
  }
}

// 兼容 body.cookie / body.cookies / body.data.cookie / body.data.cookies
function absorbLoginCookies(body) {
  if (!body || typeof body !== 'object') return
  absorbCookieValue(body.cookie)
  absorbCookieValue(body.cookies)
  absorbCookieValue(body.data?.cookie)
  absorbCookieValue(body.data?.cookies)
  // 兜底：极少数返回会把 MUSIC_U / __csrf 直接放在 body 或 body.data
  for (const src of [body, body.data]) {
    for (const k of ['MUSIC_U', '__csrf']) {
      if (typeof src?.[k] === 'string' && src[k]) jar.set(k, src[k])
    }
  }
}

function cookieHeader() {
  return [...jar].filter(([, v]) => v !== '').map(([k, v]) => `${k}=${v}`).join('; ')
}

// 用指定 NMTID 构造 Cookie 头，其它 cookie 保持 jar 里的值
function cookieHeaderWithNmtid(nmtid) {
  const parts = [...jar]
    .filter(([k, v]) => k !== 'NMTID' && v !== '')
    .map(([k, v]) => `${k}=${v}`)
  if (nmtid) parts.push(`NMTID=${nmtid}`)
  return parts.join('; ')
}

function findNmtid(headers) {
  const raw = headers?.['set-cookie'] ?? headers?.['Set-Cookie']
  const list = Array.isArray(raw) ? raw : raw ? [raw] : []
  let found = null
  for (const line of list) {
    const m = /(?:^|;\s*)NMTID=([^;]+)/.exec(String(line))
    if (m) found = m[1]
  }
  return found
}

function httpPost(urlPath, body, opts = {}) {
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(body, 'utf8')
    const headers = {
      'User-Agent': UA,
      Referer: 'https://music.163.com/',
      Origin: 'https://music.163.com',
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9',
      'Content-Type': 'application/x-www-form-urlencoded',
      'Content-Length': payload.length,
      Cookie: opts.cookie ?? cookieHeader(),
    }
    if (realIP) headers['X-Real-IP'] = realIP

    const req = https.request(
      { hostname: 'music.163.com', port: 443, path: urlPath, method: 'POST', headers, timeout: REQ_TIMEOUT },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('error', reject)
        res.on('end', () => {
          if (opts.absorbCookies !== false) absorbSetCookie(res.headers['set-cookie'])
          const text = Buffer.concat(chunks).toString('utf8')
          let json = null
          try {
            json = JSON.parse(text)
          } catch {
            // 非 JSON（网关错误页等）保留原文，交给调用方打印
          }
          resolve({ status: res.statusCode || 0, headers: res.headers, text, json })
        })
      },
    )
    req.on('timeout', () => req.destroy(new Error(`单请求超时（${REQ_TIMEOUT / 1000}s）`)))
    req.on('error', reject)
    req.end(payload)
  })
}

// 统一走 weapi；429/5xx 和网络错误各重试一次，其余错误原样返回
async function request(api, data, opts = {}) {
  const useCookie = opts.useCookie !== false
  const csrf = useCookie ? jar.get('__csrf') || '' : ''
  const apiPath = api.replace(/^\/api/, '/weapi') + `?csrf_token=${encodeURIComponent(csrf)}`
  const payloadData = { ...data, csrf_token: csrf }
  let lastErr = null

  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      const enc = weapi(payloadData)
      const body = `params=${encodeURIComponent(enc.params)}&encSecKey=${encodeURIComponent(enc.encSecKey)}`
      const res = await httpPost(apiPath, body)
      if ((res.status === 429 || res.status >= 500) && attempt < 2) {
        console.warn(`  [http] ${res.status}，600ms 后重试`)
        await sleep(600)
        continue
      }
      return res
    } catch (e) {
      lastErr = e
      if (attempt < 2) {
        console.warn(`  [net] ${e.message}，600ms 后重试`)
        await sleep(600)
      }
    }
  }
  throw lastErr || new Error('请求失败')
}

// ---------- 登录态 ----------

function loadCookie() {
  if (!fs.existsSync(COOKIE_FILE)) return false
  try {
    const obj = JSON.parse(fs.readFileSync(COOKIE_FILE, 'utf8'))
    const cookies = obj.cookies || obj
    for (const [k, v] of Object.entries(cookies)) {
      if (typeof v === 'string' && k !== 'savedAt') jar.set(k, v)
    }
    return jar.has('MUSIC_U')
  } catch (e) {
    console.warn(`[cookie] 读取失败：${e.message}`)
    return false
  }
}

function saveCookie(file = COOKIE_FILE) {
  const cookies = Object.fromEntries(jar)
  fs.writeFileSync(file, JSON.stringify({ savedAt: new Date().toISOString(), cookies }, null, 2))
  try {
    fs.chmodSync(file, 0o600)
  } catch {
    // Windows 上可能不支持，忽略
  }
  return cookies
}

// ---------- 关键字段提取 ----------

function pickFields(res, songId, level) {
  const body = res.json || {}
  const item = Array.isArray(body.data) ? body.data[0] : null
  let verdict = '无数据'
  if (!res.json) verdict = '请求失败'
  else if (res.status !== 200 || body.code !== 200) verdict = '接口失败'
  else if (!item) verdict = '无数据'
  else if (!item.url) verdict = '空地址'
  else if (item.freeTrialInfo) verdict = '仅试听'
  else verdict = '有地址'

  return {
    songId,
    requestedLevel: level,
    httpStatus: res.status,
    code: body.code ?? null,
    message: body.message ?? body.msg ?? '',
    songCode: item?.code ?? null,
    hasUrl: Boolean(item?.url),
    url: item?.url || null,
    br: item?.br ?? null,
    size: item?.size ?? null,
    freeTrialInfo: item?.freeTrialInfo ?? null,
    level: item?.level ?? null,
    encodeType: item?.encodeType ?? null,
    md5: item?.md5 ?? null,
    type: item?.type ?? null,
    verdict,
  }
}

function failRecord(songId, level, message) {
  return {
    songId,
    requestedLevel: level,
    httpStatus: null,
    code: null,
    message,
    songCode: null,
    hasUrl: false,
    url: null,
    br: null,
    size: null,
    freeTrialInfo: null,
    level: null,
    encodeType: null,
    md5: null,
    type: null,
    verdict: '请求失败',
  }
}

async function fetchSongUrl(id, level, opts = {}) {
  try {
    const res = await request(
      '/api/song/enhance/player/url/v1',
      { ids: JSON.stringify([id]), level, encodeType: 'flac' },
      opts,
    )
    return pickFields(res, id, level)
  } catch (e) {
    return failRecord(id, level, e.message)
  }
}

async function getAccount() {
  try {
    const res = await request('/api/nuser/account/get', {}, { useCookie: true })
    const b = res.json || {}
    return {
      httpStatus: res.status,
      code: b.code ?? null,
      message: b.message ?? b.msg ?? '',
      loggedIn: b.code === 200 && Boolean(b.account?.id ?? b.profile?.userId),
      userId: b.profile?.userId ?? b.account?.id ?? null,
      nickname: b.profile?.nickname ?? null,
    }
  } catch (e) {
    return { httpStatus: null, code: null, message: e.message, loggedIn: false, userId: null, nickname: null }
  }
}

// 803 成功后统一处理：吸收 cookie → 落盘 → 立刻调 user/account 验证
async function finishQrLogin(body, opts = {}) {
  absorbLoginCookies(body)
  if (!jar.has('MUSIC_U')) {
    return { ok: false, stage: 'no-music-u', cookies: Object.fromEntries(jar) }
  }

  const file = opts.cookieFile || COOKIE_FILE
  saveCookie(file)
  const account = await (opts.accountCheck || getAccount)()
  if (!account.loggedIn) return { ok: false, stage: 'account-check', cookies: Object.fromEntries(jar), account }

  // 校验过程中可能补发 cookie，成功后再落一次
  const cookies = saveCookie(file)
  return { ok: true, cookies, account }
}

// 脱敏：字符串只留前 12 个字符，保留所有字段名
function maskDeep(value) {
  if (typeof value === 'string') return value.length > 12 ? value.slice(0, 12) + '…' : value
  if (Array.isArray(value)) return value.map(maskDeep)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, maskDeep(v)]))
  }
  return value
}

// ---------- 扫码路由 / 取证 ----------

// 明文路由：POST /api/...，表单 body，不走 weapi 加密
async function requestPlain(urlPath, form, opts = {}) {
  const body = Object.entries(form)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  let lastErr = null
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      const res = await httpPost(urlPath, body, opts)
      if ((res.status === 429 || res.status >= 500) && attempt < 2) {
        console.warn(`  [http] ${res.status}，600ms 后重试`)
        await sleep(600)
        continue
      }
      return res
    } catch (e) {
      lastErr = e
      if (attempt < 2) {
        console.warn(`  [net] ${e.message}，600ms 后重试`)
        await sleep(600)
      }
    }
  }
  throw lastErr || new Error('请求失败')
}

const API_QR_CHECK = '/api/login/qrcode/client/login?type=3'
const API_QR_CREATE = '/api/login/qrcode/client/login?type=1'

function checkWeapi(unikey) {
  return request('/api/login/qrcode/client/login', { key: unikey, type: 3 })
}

function checkApi(unikey) {
  const csrf = jar.get('__csrf') || ''
  return requestPlain(API_QR_CHECK, { key: unikey, csrf_token: csrf })
}

function checkRoute(route, unikey) {
  return route === 'api' ? checkApi(unikey) : checkWeapi(unikey)
}

// trace 里的响应头：set-cookie 只留前 40 字符 + 长度，其余全量
function sanitizeTraceHeaders(headers) {
  const out = {}
  for (const [k, v] of Object.entries(headers || {})) {
    if (k.toLowerCase() === 'set-cookie') {
      const list = Array.isArray(v) ? v : [v]
      out[k] = list.map((s) => {
        const str = String(s)
        return { preview: str.slice(0, 40), length: str.length }
      })
    } else {
      out[k] = v
    }
  }
  return out
}

function makeTrace(step, route, res, error) {
  const body = res?.json
  return {
    step,
    route,
    ts: new Date().toISOString(),
    status: res?.status ?? null,
    headers: sanitizeTraceHeaders(res?.headers),
    bodyKeys: body && typeof body === 'object' ? Object.keys(body) : [],
    code: body?.code ?? null,
    ...(error ? { error } : {}),
  }
}

function writeJson(file, value) {
  fs.writeFileSync(file, JSON.stringify(value, null, 2))
  try {
    fs.chmodSync(file, 0o600)
  } catch {
    // Windows 上可能不支持，忽略
  }
}

// qr-dual.json 用：完整 status / headers / body 原文，set-cookie 不截断
function serializeResponse(route, res) {
  return {
    route,
    ts: new Date().toISOString(),
    status: res?.status ?? null,
    headers: res?.headers || {},
    bodyText: res?.text ?? '',
    body: res?.json ?? null,
    code: res?.json?.code ?? null,
    ...(res?.error ? { error: res.error } : {}),
  }
}

function snapshotCookies() {
  const names = [...jar.keys()]
  return {
    names,
    musicCookies: names.filter((n) => /^MUSIC_/i.test(n)),
    hasMUSIC_U: jar.has('MUSIC_U'),
    hasCsrf: jar.has('__csrf'),
  }
}

function responseHasMusicU(res) {
  const raw = res?.headers?.['set-cookie'] ?? res?.headers?.['Set-Cookie']
  const list = Array.isArray(raw) ? raw : raw ? [raw] : []
  const headerHas = list.some((v) => /(?:^|[;\s])MUSIC_U=/i.test(String(v)))
  const cookie =
    res?.json?.cookie ?? res?.json?.cookies ?? res?.json?.data?.cookie ?? res?.json?.data?.cookies
  let bodyHas = false
  if (typeof cookie === 'string') bodyHas = /(?:^|[;\s])MUSIC_U=/i.test(cookie)
  else if (cookie && typeof cookie === 'object') bodyHas = Object.keys(cookie).some((k) => k.toLowerCase() === 'music_u')
  if (res?.json?.MUSIC_U || res?.json?.data?.MUSIC_U) bodyHas = true
  return { headerHas, bodyHas }
}

async function safeRequest(api, data, opts) {
  try {
    return await request(api, data, opts)
  } catch (e) {
    return { status: null, headers: {}, text: '', json: null, error: e.message }
  }
}

// 803 后立即用同一 key 对照两条路由，并做 user/account + login/refresh 收尾
async function runDualCapture(triggerRoute, triggerRes, unikey, tracePush, opts = {}) {
  const dualFile = opts.dualFile || path.join(OUT, 'qr-dual.json')
  const cookieFile = opts.cookieFile || COOKIE_FILE
  const check = opts.checkRoute || checkRoute
  const req = opts.safeRequest || safeRequest
  const apiCall = opts.apiCall || requestPlain
  const nmtidBefore = opts.nmtidBefore ?? null
  absorbLoginCookies(triggerRes.json)

  const safeCall = async (fn) => {
    try {
      return await fn()
    } catch (e) {
      return { status: null, headers: {}, text: '', json: null, error: e.message }
    }
  }

  // 803 后先 weapi 复测，再明文 api 复测（顺序固定）
  const captures = []
  for (const route of ['weapi', 'api']) {
    const res = await safeCall(() => check(route, unikey))
    absorbLoginCookies(res.json)
    tracePush('dual-capture', route, res)
    captures.push(serializeResponse(route, res))
  }

  const apiCapture = captures.find((c) => c.route === 'api')
  const apiEvidence = apiCapture ? responseHasMusicU({ headers: apiCapture.headers, json: apiCapture.body }) : { headerHas: false, bodyHas: false }

  // b 的响应头带 MUSIC_U 才补明文 type=1 create
  let apiCreate = null
  let apiCreateSkipped = 'api capture 响应头没有 MUSIC_U Set-Cookie'
  if (apiEvidence.headerHas) {
    const form = { key: unikey, csrf_token: jar.get('__csrf') || '' }
    const res = await safeCall(() => apiCall(API_QR_CREATE, form))
    absorbLoginCookies(res.json)
    tracePush('api-create', 'api', res)
    apiCreate = serializeResponse('api', res)
    apiCreateSkipped = null
  }

  // 803 后响应头里最新下发的 NMTID（按 trigger → weapi → api → apiCreate 顺序取最后一个）
  let latestNmtid = findNmtid(triggerRes.headers)
  for (const c of captures) {
    const v = findNmtid(c.headers)
    if (v) latestNmtid = v
  }
  if (apiCreate) {
    const v = findNmtid(apiCreate.headers)
    if (v) latestNmtid = v
  }

  // 额外对照：旧 NMTID（803 前 jar 里的） vs 803 后响应头最新 NMTID
  const nmtidCompare = {
    before: nmtidBefore,
    latestFromHeaders: latestNmtid,
    same: Boolean(nmtidBefore && latestNmtid && nmtidBefore === latestNmtid),
    skipped: null,
  }
  if (latestNmtid) {
    const form = { key: unikey, csrf_token: jar.get('__csrf') || '' }
    const baseRes = await safeCall(() =>
      apiCall(API_QR_CHECK, form, { cookie: cookieHeaderWithNmtid(nmtidBefore), absorbCookies: false }),
    )
    const latestRes = await safeCall(() =>
      apiCall(API_QR_CHECK, form, { cookie: cookieHeaderWithNmtid(latestNmtid), absorbCookies: false }),
    )
    tracePush('nmtid-compare', 'api', baseRes)
    tracePush('nmtid-compare', 'api', latestRes)
    nmtidCompare.baseline = serializeResponse('api', baseRes)
    nmtidCompare.latest = serializeResponse('api', latestRes)
    nmtidCompare.differs =
      baseRes.status !== latestRes.status ||
      (baseRes.json?.code ?? null) !== (latestRes.json?.code ?? null) ||
      baseRes.text !== latestRes.text
    nmtidCompare.note = nmtidCompare.differs
      ? '两次结果不同：cookie 会话可能绑定 NMTID'
      : '两次结果一致（或 NMTID 未变化）'
  } else {
    nmtidCompare.skipped = '803 后的响应头没有下发 NMTID'
  }

  const cookiesAfterCapture = snapshotCookies()
  const triggerEvidence = responseHasMusicU({ headers: triggerRes.headers, json: triggerRes.json })
  const evidence = captures.map((c) => ({ route: c.route, ...responseHasMusicU({ headers: c.headers, json: c.body }) }))
  if (apiCreate) evidence.push({ route: 'api-create', ...responseHasMusicU({ headers: apiCreate.headers, json: apiCreate.body }) })

  if (jar.has('MUSIC_U')) saveCookie(cookieFile)

  const report = {
    generatedAt: new Date().toISOString(),
    unikey,
    trigger: serializeResponse(triggerRoute, triggerRes),
    triggerEvidence,
    captures,
    apiCreate,
    apiCreateSkipped,
    nmtidCompare,
    evidence,
    cookiesAfterCapture,
  }
  writeJson(dualFile, report)

  // 收尾 1：user/account（可能暴露出 MUSIC_A 之类的登录 cookie）
  const accountRes = await req('/api/nuser/account/get', {}, { useCookie: true })
  tracePush('account', 'weapi', accountRes)
  const ab = accountRes.json || {}
  const account = {
    ...serializeResponse('weapi', accountRes),
    loggedIn: ab.code === 200 && Boolean(ab.account?.id ?? ab.profile?.userId),
    nickname: ab.profile?.nickname ?? null,
    userId: ab.profile?.userId ?? ab.account?.id ?? null,
  }

  // 收尾 2：login/refresh（看能否换出新的 cookie）
  const refreshRes = await req('/api/login/token/refresh', {}, { useCookie: true })
  absorbLoginCookies(refreshRes.json)
  tracePush('refresh', 'weapi', refreshRes)

  const cookiesAfterRefresh = snapshotCookies()
  if (jar.has('MUSIC_U')) saveCookie(cookieFile)

  const hasEvidence =
    triggerEvidence.headerHas ||
    triggerEvidence.bodyHas ||
    evidence.some((e) => e.headerHas || e.bodyHas) ||
    cookiesAfterCapture.hasMUSIC_U ||
    cookiesAfterRefresh.hasMUSIC_U ||
    account.loggedIn

  const hits = []
  if (triggerEvidence.headerHas) hits.push('trigger.set-cookie')
  if (triggerEvidence.bodyHas) hits.push('trigger.body.cookie')
  for (const e of evidence) {
    if (e.headerHas) hits.push(`${e.route}.set-cookie`)
    if (e.bodyHas) hits.push(`${e.route}.body.cookie`)
  }
  if (cookiesAfterCapture.hasMUSIC_U) hits.push('cookiesAfterCapture.MUSIC_U')
  if (cookiesAfterRefresh.hasMUSIC_U) hits.push('cookiesAfterRefresh.MUSIC_U')
  if (account.loggedIn) hits.push('account.loggedIn')

  const verdict = hasEvidence
    ? `803 带登录态证据：${hits.join(', ')}`
    : '803 未带登录态：trigger/两条 capture 的响应头和 body 都没有 MUSIC_U，cookie 快照没有 MUSIC_U，user/account 未登录'

  const refreshReport = serializeResponse('weapi', refreshRes)
  Object.assign(report, {
    account,
    refresh: refreshReport,
    cookiesAfterRefresh,
    hits,
    verdict,
  })
  writeJson(dualFile, report)

  const short = (s) => (s ? String(s).slice(0, 8) + '…' : '-')
  console.log(`[qr][dual] 803 双路由取证完成: ${dualFile}`)
  console.log(`[qr][dual] 触发路由: ${triggerRoute}`)
  console.log(`[qr][dual] 复测: ${captures.map((c) => `${c.route}=${c.code ?? c.status ?? 'ERR'}`).join(', ')}`)
  console.log(
    apiCreate
      ? `[qr][dual] api type=1 create: HTTP ${apiCreate.status ?? '-'} code=${apiCreate.code ?? '-'}`
      : `[qr][dual] api type=1 create: 跳过（${apiCreateSkipped}）`,
  )
  if (nmtidCompare.skipped) {
    console.log(`[qr][dual] NMTID 对照: 跳过（${nmtidCompare.skipped}）`)
  } else {
    console.log(
      `[qr][dual] NMTID 对照: before=${short(nmtidCompare.before)} latest=${short(nmtidCompare.latestFromHeaders)} same=${nmtidCompare.same} differs=${nmtidCompare.differs}`,
    )
  }
  console.log(`[qr][dual] cookie 名: ${cookiesAfterCapture.names.join(', ') || '(空)'}`)
  console.log(`[qr][dual] MUSIC_U 命中: ${hits.length ? hits.join(', ') : '无'}`)
  console.log(
    `[qr][dual] user/account: HTTP ${account.status ?? '-'} code=${account.code ?? '-'} loggedIn=${account.loggedIn} profile.nickname=${account.nickname ?? '-'} profile.userId=${account.userId ?? '-'}`,
  )
  const refreshBody = refreshReport.body || {}
  console.log(
    `[qr][dual] login/refresh: HTTP ${refreshReport.status ?? '-'} code=${refreshReport.code ?? '-'} message=${refreshBody.message || refreshBody.msg || refreshReport.error || '-'}`,
  )
  console.log(`[qr][dual] 结论: ${verdict}`)
  if (account.loggedIn && jar.has('MUSIC_U')) console.log('[qr][dual] cookie.json 已保存，可继续跑 test。')
  else if (account.loggedIn) console.log('[qr][dual] user/account 已登录，但 cookie 里没有 MUSIC_U；当前 test 会拒绝，先看 qr-dual.json 里的 cookie 名。')
  return account.loggedIn ? 0 : 1
}

async function handleSingle803(route, res) {
  const b = res.json || {}
  console.log(`[qr][${route}] 803 登录成功，保存 cookie 并校验账号…`)
  const result = await finishQrLogin(b)

  if (!result.ok && result.stage === 'no-music-u') {
    console.error(`[qr][${route}] 803 但响应体和响应头里都没有 MUSIC_U`)
    console.error(`[qr][${route}] 当前 cookie: ${[...jar.keys()].join(', ') || '(空)'}`)
    console.error(`[qr][${route}] 响应头 Set-Cookie: ${res.headers?.['set-cookie'] ? '有（未含 MUSIC_U）' : '无'}`)
    console.error(`[qr][${route}] 803 响应体（脱敏：字段名 + 前 12 字符）：`)
    console.error(JSON.stringify(maskDeep(b), null, 2))
    return 1
  }

  if (!result.ok) {
    const a = result.account || {}
    console.error(`[qr][${route}] 803 但账号校验不通过：HTTP ${a.httpStatus ?? '-'} / code ${a.code ?? '-'} / ${a.message || '无 message'}`)
    if (a.httpStatus === 200) {
      console.error('[qr] 登录未成功，稍后会恢复运行前的 cookie.json（如果有）')
    } else {
      console.error(`[qr] cookie 已保存，但校验请求失败；可稍后运行 test 再确认。路径: ${COOKIE_FILE}`)
    }
    return 1
  }

  const a = result.account
  console.log(`[qr][${route}] 803 登录成功，cookie 已保存: ${COOKIE_FILE}`)
  console.log(`[qr][${route}] 包含: ${Object.keys(result.cookies).join(', ')}`)
  if (!jar.has('__csrf')) console.warn(`[qr][${route}] 注意：cookie 中仍没有 __csrf，部分接口可能异常`)
  console.log(`[qr][${route}] 账号校验通过：code=${a.code ?? '-'} profile.nickname=${a.nickname ?? '(未知)'} profile.userId=${a.userId ?? '?'}`)
  return 0
}

// ---------- 输出 ----------

function textWidth(s) {
  let w = 0
  for (const ch of String(s)) {
    const c = ch.codePointAt(0)
    const wide =
      (c >= 0x2e80 && c <= 0xa4cf) ||
      (c >= 0xac00 && c <= 0xd7a3) ||
      (c >= 0xf900 && c <= 0xfaff) ||
      (c >= 0xfe30 && c <= 0xfe4f) ||
      (c >= 0xff00 && c <= 0xff60) ||
      (c >= 0xffe0 && c <= 0xffe6)
    w += wide ? 2 : 1
  }
  return w
}

const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - textWidth(s)))

function fmtTrial(v) {
  if (v == null) return '-'
  if (typeof v === 'object') {
    if ('start' in v || 'end' in v) return `${v.start ?? '?'}-${v.end ?? '?'}`
    return JSON.stringify(v)
  }
  return String(v)
}

function printRecord(r) {
  console.log('--- 关键字段 ---')
  console.log(`HTTP 状态 : ${r.httpStatus ?? '-'}`)
  console.log(`业务 code : ${r.code ?? '-'}   message: ${r.message || '-'}`)
  console.log(`单曲 code : ${r.songCode ?? '-'}`)
  console.log(`url       : ${r.hasUrl ? '有' : '空'}${r.url ? '  ' + (r.url.length > 120 ? r.url.slice(0, 120) + '…' : r.url) : ''}`)
  console.log(`br        : ${r.br ?? '-'}`)
  console.log(`size      : ${r.size ?? '-'}`)
  console.log(`freeTrial : ${JSON.stringify(r.freeTrialInfo)}`)
  console.log(`level     : ${r.level ?? '-'}`)
  console.log(`encodeType: ${r.encodeType ?? '-'}`)
  console.log(`md5       : ${r.md5 ?? '-'}`)
  console.log(`判定      : ${r.verdict}`)
}

function shortRec(r) {
  if (r.httpStatus == null) return `请求失败：${r.message}`
  const bad = r.httpStatus !== 200 || r.code !== 200
  return `HTTP ${r.httpStatus} code ${r.code ?? '-'} 单曲 ${r.songCode ?? '-'} → ${r.verdict}${bad && r.message ? ` | ${r.message}` : ''}`
}

function printTable(results) {
  const header = ['歌曲ID', '档位', 'HTTP', 'code', 'url', 'br', 'size', 'freeTrial', 'level', 'encodeType', 'md5', '判定']
  const rows = results.map((r) => [
    r.songId,
    r.requestedLevel,
    r.httpStatus ?? '-',
    r.songCode ?? r.code ?? '-',
    r.hasUrl ? '有' : '空',
    r.br ?? '-',
    r.size ?? '-',
    fmtTrial(r.freeTrialInfo),
    r.level ?? '-',
    r.encodeType ?? '-',
    r.md5 ?? '-',
    r.verdict,
  ])
  const widths = header.map((h, i) => Math.max(textWidth(h), ...rows.map((row) => textWidth(row[i]))))
  const line = (cells) => cells.map((c, i) => pad(c, widths[i])).join('  ')
  console.log('\n' + line(header))
  console.log(widths.map((w) => '-'.repeat(w)).join('  '))
  for (const row of rows) console.log(line(row))
}

function buildReport(results, account) {
  const bySong = SONGS.map((id) => {
    const rs = results.filter((r) => r.songId === id)
    const full = rs.filter((r) => r.verdict === '有地址').length
    const trial = rs.filter((r) => r.verdict === '仅试听').length
    const empty = rs.filter((r) => r.verdict === '空地址').length
    const failed = rs.length - full - trial - empty
    return { id, full, trial, empty, failed, playable: full + trial > 0 }
  })
  const allFull = bySong.every((s) => s.full > 0)
  const allPlayable = bySong.every((s) => s.playable)

  return {
    generatedAt: new Date().toISOString(),
    baseUrl: BASE,
    songs: SONGS,
    levels: LEVELS,
    account,
    results,
    summary: {
      total: results.length,
      full: results.filter((r) => r.verdict === '有地址').length,
      trial: results.filter((r) => r.verdict === '仅试听').length,
      empty: results.filter((r) => r.verdict === '空地址').length,
      failed: results.filter((r) => !['有地址', '仅试听', '空地址'].includes(r.verdict)).length,
      bySong,
      verdict: allFull
        ? '三首均至少一个档位有完整地址 → 网页端链路可行'
        : allPlayable
          ? '三首均可播，但部分只有试听片段 → 需确认是否可接受'
          : '存在完全拿不到地址的歌曲 → 按方案 3.3 节重新评估',
    },
  }
}

// ---------- 子命令 ----------

function printHelp() {
  console.log(`网易云 weapi 探针 · M0 可行性验证

用法:
  node probe.mjs help
  node probe.mjs qr   [--timeout=300] [--route=weapi|api] [--dual] [--dump] [--real-ip=1.2.3.4]
  node probe.mjs url  <songId> [--level=exhigh] [--real-ip=1.2.3.4]
  node probe.mjs test [--real-ip=1.2.3.4]
  node probe.mjs selftest

子命令:
  qr        输出 unikey 和二维码 URL；PNG 由外部 Python qrcode 生成；轮询扫码并写 cookie.json
  url       探测单首单档，打印并落盘关键字段（有 cookie 就用）
  test      三首 × 四档全测，生成 out/report.json 并打印表格
  selftest  不带登录 cookie 匿名请求一次，验证网络与 weapi 加密链路

音质档位:
  standard | higher | exhigh | lossless

参数:
  --timeout=秒    qr 等待扫码上限，默认 300
  --route=路由    qr 的 check 路由：weapi（默认）或 api（明文 /api/...）
  --dual          qr 等待期只用 weapi，803 后再做 api + weapi 对照，写 out/qr-dual.json
  --dump          qr 专用：把最后一次 check 的原始 JSON 写到 out/qr-final.json（含登录态）
  --level=档位    url 的音质档位，默认 exhigh
  --real-ip=IP    可选，附加 X-Real-IP 请求头；默认不发

文件:
  cookie.json    登录态（MUSIC_U、__csrf 等），删除即退出登录
  out/qr.png     可选，由外部 Python qrcode 生成；本探针不内置编码器
  out/qr-trace.json  qr 全流程响应头 trace（set-cookie 截断）
  out/qr-dual.json   仅 --dual 且 803 时生成：weapi→api 完整响应 + api create + NMTID 对照 + 收尾探测
  out/qr-final.json  仅 --dump 时生成，含登录态，不要外传
  out/report.json  test 结果
  out/url-<id>-<level>.json  url 单次结果

退出码: 0 正常；1 请求/登录失败；2 参数错误`)
}

async function cmdQr(timeoutSec, opts = {}) {
  const dual = Boolean(opts.dual)
  const route = opts.route || 'weapi'
  const dump = Boolean(opts.dump)
  fs.mkdirSync(OUT, { recursive: true })

  const trace = []
  const traceFile = path.join(OUT, 'qr-trace.json')
  const tracePush = (step, r, res, error) => {
    trace.push(makeTrace(step, r, res, error))
    try {
      writeJson(traceFile, trace)
    } catch (e) {
      console.warn(`[qr] 写 qr-trace.json 失败：${e.message}`)
    }
  }

  // 旧版自制二维码已证伪：不再生成本地 PNG；不碰用户已经生成好的 out/qr.png
  const qrFile = path.join(OUT, 'qr.png')
  if (dump) fs.rmSync(path.join(OUT, 'qr-final.json'), { force: true })

  console.log('[qr] 1/3 申请二维码 key（weapi）…')
  let keyRes
  try {
    keyRes = await request('/api/login/qrcode/unikey', { type: 1 })
  } catch (e) {
    tracePush('unikey', 'weapi', null, e.message)
    console.error(`[qr] 申请 key 失败：${e.message}`)
    console.error(`[qr] trace: ${traceFile}`)
    return 1
  }
  tracePush('unikey', 'weapi', keyRes)
  const kb = keyRes.json || {}
  const unikey = kb?.data?.unikey || kb?.unikey
  if (keyRes.status !== 200 || kb.code !== 200 || !unikey) {
    console.error(`[qr] 失败：HTTP ${keyRes.status} / code ${kb.code ?? '-'} / ${kb.message || kb.msg || '无 unikey'}`)
    console.error(`[qr] trace: ${traceFile}`)
    return 1
  }
  console.log(`[qr] unikey: ${unikey}`)

  console.log('[qr] 2/3 生成二维码 URL（不内置 PNG 编码器）…')
  const constructedUrl = `https://music.163.com/login?codekey=${unikey}`
  let qrurl = constructedUrl
  let serverPng = false
  try {
    const createRes = await request('/api/login/qrcode/client/login', { key: unikey, type: 1, qrimg: true })
    tracePush('create', 'weapi', createRes)
    const cb = createRes.json || {}
    const cd = cb.data || cb
    if (cd.qrurl) qrurl = cd.qrurl
    if (cd.qrimg) {
      const m = /^data:image\/[\w.+-]+;base64,(.+)$/s.exec(cd.qrimg)
      if (m) {
        fs.writeFileSync(qrFile, Buffer.from(m[1], 'base64'))
        serverPng = true
      }
    }
  } catch (e) {
    tracePush('create', 'weapi', null, e.message)
    console.warn(`[qr] create 请求失败：${e.message}，继续用标准 URL`)
  }

  console.log(`[qr] 二维码 URL: ${qrurl}`)
  if (serverPng) {
    console.log(`[qr] 服务端 qrimg 已保存: ${qrFile}`)
  } else {
    const pyPath = qrFile.replace(/\\/g, '/')
    console.log('[qr] 本探针不再内置二维码编码器；请用外部 Python qrcode 生成 PNG。')
    console.log('[qr] 先在 PowerShell 里设置 $PY（路径见 README），然后执行：')
    console.log(`[qr]   & $PY -c "import qrcode; qrcode.make('${qrurl}').save(r'${pyPath}')"`)
    console.log('[qr] 注意：系统 PATH 里的 python 没有 qrcode，别直接用 python。')
  }

  // --dual 等待期只打 weapi，避免两条路由同时轮询同一个 key；803 后才做 api 对照
  const routes = dual ? ['weapi'] : [route]
  console.log(
    `[qr] 3/3 等待扫码（${dual ? 'dual: 等待期只用 weapi' : route}，每 ${POLL_MS}ms 一轮，最多 ${timeoutSec}s）…`,
  )

  const deadline = Date.now() + timeoutSec * 1000
  const lastState = new Map()
  const lastPrint = new Map()
  let consecutiveErrors = 0
  let trigger = null

  const announce = (r, code, text) => {
    const key = `${code}:${text}`
    if (lastPrint.get(r) === key) return
    lastPrint.set(r, key)
    console.log(`[qr][${r}] ${code} ${text}`)
  }

  while (Date.now() < deadline && !trigger) {
    await sleep(POLL_MS)
    for (const r of routes) {
      if (Date.now() >= deadline) break
      const nmtidBefore = jar.get('NMTID') || null
      let res
      try {
        res = await checkRoute(r, unikey)
      } catch (e) {
        consecutiveErrors++
        tracePush('check', r, null, e.message)
        console.warn(`[qr][${r}] 轮询网络错误（${consecutiveErrors}/10）：${e.message}`)
        if (consecutiveErrors >= 10) {
          console.error('[qr] 连续轮询失败，停止。请检查网络后重新运行 qr。')
          console.error(`[qr] trace: ${traceFile}`)
          return 1
        }
        continue
      }
      consecutiveErrors = 0
      const b = res.json || {}
      const code = Number(b.code)

      // --dump：每次覆盖写最后一次 check 的原始 JSON（可能含登录态）
      if (dump) {
        try {
          writeJson(path.join(OUT, 'qr-final.json'), b)
        } catch (e) {
          console.warn(`[qr] 写 qr-final.json 失败：${e.message}`)
        }
      }

      const stateKey = `${res.status}:${code}`
      if (lastState.get(r) !== stateKey) {
        lastState.set(r, stateKey)
        tracePush('check', r, res)
      }

      if (code === 803) {
        announce(r, 803, '登录成功')
        trigger = { route: r, res, nmtidBefore }
        break
      }
      if (code === 800) {
        console.error(`[qr][${r}] 800 二维码已过期`)
        console.error(dual ? '[qr][dual] 等待期二维码已过期，请重新运行 qr 生成新码' : '[qr] 800 二维码已过期，请重新运行 qr 生成新码')
        console.error(`[qr] trace: ${traceFile}`)
        return 1
      }
      if (code === 802) {
        announce(r, 802, '已扫码，请在手机上确认登录')
        continue
      }
      if (code === 801) {
        announce(r, 801, '等待扫码…')
        continue
      }
      console.warn(`[qr][${r}] ${qrStatusText(b.code)}：HTTP ${res.status} / code ${b.code ?? '-'} / ${b.message || b.msg || '未知'}`)
    }
  }

  if (trigger) {
    // 保护已有登录态：本次 803 若没通过校验，恢复运行前的 cookie.json
    const oldCookie = fs.existsSync(COOKIE_FILE) ? fs.readFileSync(COOKIE_FILE) : null
    const rc = dual
      ? await runDualCapture(trigger.route, trigger.res, unikey, tracePush, { nmtidBefore: trigger.nmtidBefore })
      : await handleSingle803(trigger.route, trigger.res)
    if (rc !== 0) {
      if (oldCookie) {
        fs.writeFileSync(COOKIE_FILE, oldCookie)
        console.error('[qr] 已恢复运行前的 cookie.json')
      } else if (fs.existsSync(COOKIE_FILE)) {
        fs.rmSync(COOKIE_FILE, { force: true })
        console.error('[qr] 已清理本次未通过校验的 cookie.json')
      }
    }
    return rc
  }

  console.error(`[qr] 等待超时（${timeoutSec}s，${dual ? 'dual: waiting weapi' : route}），未完成登录。请重新运行 qr。`)
  console.error(`[qr] trace: ${traceFile}`)
  if (dual && fs.existsSync(path.join(OUT, 'qr-dual.json'))) {
    console.error('[qr][dual] 注意：out/qr-dual.json 是旧文件；本次没有拿到 803，未覆盖它。')
  }
  return 1
}

async function cmdUrl(id, level) {
  if (!/^\d+$/.test(id || '')) {
    console.error('用法: node probe.mjs url <songId> [--level=exhigh]')
    return 2
  }
  loadCookie()
  console.log(`[url] song=${id} level=${level} cookie=${jar.has('MUSIC_U') ? '有' : '无（匿名）'}`)
  const rec = await fetchSongUrl(id, level)
  printRecord(rec)

  fs.mkdirSync(OUT, { recursive: true })
  const file = path.join(OUT, `url-${id}-${level}.json`)
  fs.writeFileSync(file, JSON.stringify({ generatedAt: new Date().toISOString(), ...rec }, null, 2))
  console.log(`[url] 已落盘: ${file}`)
  return ['有地址', '仅试听', '空地址'].includes(rec.verdict) ? 0 : 1
}

async function cmdTest() {
  loadCookie()
  if (!jar.has('MUSIC_U')) {
    console.error('[test] 没有登录态：cookie.json 缺少 MUSIC_U')
    console.error('[test] 请先运行: node probe.mjs qr')
    return 1
  }
  fs.mkdirSync(OUT, { recursive: true })
  console.log('[test] 登录态已加载（cookie.json）')

  const account = await getAccount()
  if (account.loggedIn) {
    console.log(`[test] 登录账号: ${account.nickname ?? '(未知昵称)'} (id ${account.userId ?? '?'})`)
  } else if (account.httpStatus === 200) {
    console.error(`[test] 登录态无效或已过期（user/account 返回 code ${account.code ?? '-'}，没有账号信息）`)
    console.error('[test] 请重新运行: node probe.mjs qr')
    return 1
  } else {
    const msg = account.message || '无 message'
    console.warn(`[test] 登录态校验请求失败：HTTP ${account.httpStatus ?? '-'} / code ${account.code ?? '-'} / ${msg}（继续测试）`)
  }

  const results = []
  const total = SONGS.length * LEVELS.length
  let i = 0
  for (const id of SONGS) {
    for (const level of LEVELS) {
      i++
      process.stdout.write(`[test] ${i}/${total} ${id} ${level} … `)
      const rec = await fetchSongUrl(id, level)
      results.push(rec)
      console.log(shortRec(rec))
      await sleep(200)
    }
  }

  const report = buildReport(results, account)
  const file = path.join(OUT, 'report.json')
  fs.writeFileSync(file, JSON.stringify(report, null, 2))

  printTable(results)
  console.log('\n--- 汇总 ---')
  for (const s of report.summary.bySong) {
    console.log(`${s.id}: 完整 ${s.full}/4，试听 ${s.trial}/4，空地址 ${s.empty}/4，失败 ${s.failed}/4 → ${s.playable ? '可播' : '不可播'}`)
  }
  console.log(`结论: ${report.summary.verdict}`)
  console.log(`[test] 报告: ${file}`)
  return 0
}

async function cmdSelftest() {
  console.log('[selftest] 匿名 weapi 请求（不加载 cookie.json，不带登录态）')
  console.log(`[selftest] 目标: ${BASE}/weapi/song/enhance/player/url/v1  id=${SONGS[0]} level=standard`)
  const t0 = Date.now()
  const rec = await fetchSongUrl(SONGS[0], 'standard', { useCookie: false })
  console.log(`[selftest] HTTP 状态: ${rec.httpStatus ?? '-'}`)
  console.log(`[selftest] 业务 code: ${rec.code ?? '-'}  message: ${rec.message || '-'}`)
  console.log(`[selftest] 单曲 code: ${rec.songCode ?? '-'}  url: ${rec.hasUrl ? '有' : '空'}  level: ${rec.level ?? '-'}`)
  console.log(`[selftest] 耗时: ${Date.now() - t0}ms`)

  if (rec.httpStatus == null) {
    console.error(`[selftest] 失败：${rec.message}`)
    return 1
  }
  if (rec.httpStatus !== 200 || rec.code !== 200) {
    console.error('[selftest] HTTP 或业务 code 异常，加密链路可能有问题')
    return 1
  }
  console.log('[selftest] 通过：网络可达，weapi 参数被服务端接受')
  return 0
}

// ---------- CLI ----------

function parseFlags(list) {
  const out = { _: [] }
  for (let i = 0; i < list.length; i++) {
    const token = list[i]
    if (!token.startsWith('--')) {
      out._.push(token)
      continue
    }
    const body = token.slice(2)
    const eq = body.indexOf('=')
    if (eq >= 0) {
      out[body.slice(0, eq)] = body.slice(eq + 1)
    } else if (list[i + 1] && !list[i + 1].startsWith('--')) {
      out[body] = list[i + 1]
      i++
    } else {
      out[body] = true
    }
  }
  return out
}

function parseTimeout(v) {
  if (v === undefined) return 300
  const n = v === true ? NaN : Number(v)
  if (!Number.isFinite(n) || n <= 0) {
    console.error(`[参数] --timeout=${v} 无效，需要正整数秒`)
    return null
  }
  return Math.min(Math.floor(n), 1800)
}

function parseLevel(v) {
  const level = String(v || 'exhigh').toLowerCase()
  if (!LEVELS.includes(level)) {
    console.error(`[参数] --level 只能是: ${LEVELS.join(' / ')}`)
    return null
  }
  return level
}

function parseRoute(v) {
  const route = String(v || 'weapi').toLowerCase()
  if (!['weapi', 'api'].includes(route)) {
    console.error('[参数] --route 只能是: weapi / api')
    return null
  }
  return route
}

async function guard(name, fn) {
  try {
    return await fn()
  } catch (e) {
    console.error(`[${name}] 失败：${e.message}`)
    return 1
  }
}

async function main() {
  const argv = process.argv.slice(2)
  const cmd = argv[0] || 'help'
  const args = parseFlags(argv.slice(1))
  const rawRealIP = args['real-ip'] ?? args['x-real-ip'] ?? ''
  if (rawRealIP === true) {
    console.error('[参数] --real-ip 需要跟一个 IP')
    return 2
  }
  realIP = String(rawRealIP).trim()
  if (/[\r\n]/.test(realIP)) {
    console.error('[参数] X-Real-IP 含非法字符')
    return 2
  }

  switch (cmd) {
    case 'help':
    case '--help':
    case '-h':
      printHelp()
      return 0
    case 'selftest':
      return guard('selftest', cmdSelftest)
    case 'qr': {
      const timeout = parseTimeout(args.timeout)
      if (timeout == null) return 2
      const route = parseRoute(args.route)
      if (!route) return 2
      if (args.dual && args.route) console.warn('[qr] --dual 等待期只用 weapi，803 后才做 api 对照；--route 被忽略')
      return guard('qr', () => cmdQr(timeout, { route, dual: Boolean(args.dual), dump: Boolean(args.dump) }))
    }
    case 'url': {
      const level = parseLevel(args.level)
      if (!level) return 2
      return guard('url', () => cmdUrl(args._[0], level))
    }
    case 'test':
      return guard('test', cmdTest)
    default:
      console.error(`未知子命令: ${cmd}\n`)
      printHelp()
      return 2
  }
}

const isDirectRun = Boolean(process.argv[1]) && path.resolve(process.argv[1]) === import.meta.filename

if (isDirectRun) {
  main()
    .then((code) => {
      process.exitCode = code
    })
    .catch((e) => {
      console.error(`[fatal] ${e.stack || e.message}`)
      process.exitCode = 1
    })
}

// 给临时单元测试用；正常 CLI 不受影响
export { weapi, request, finishQrLogin, absorbLoginCookies, cookieHeader, jar, maskDeep, runDualCapture, sanitizeTraceHeaders, serializeResponse, responseHasMusicU }
