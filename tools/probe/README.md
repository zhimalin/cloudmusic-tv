# 网易云 weapi 探针（M0）

验证「不碰官方 TV 接口，直接走网页端 weapi」这条路是否能拿到三首测试歌的可播放地址；同时取证扫码登录的 803 到底有没有下发登录 cookie。

- 零 npm 依赖，只用 Node 内置模块（`crypto` / `https` / `fs` / `path`）
- 接口请求只发往 `music.163.com`
- 不写死账号、密码、cookie；登录只走手机扫码
- **不内置二维码编码器**：`qr` 只输出 `unikey` 和二维码 URL，PNG 用外部 Python `qrcode` 生成（旧版手写编码器已证伪并移除）

## 环境

这台机器没有把 `node` 加进 PATH，用绝对路径：

```powershell
$NODE = "C:\Users\艾莉丝喵\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
cd "C:\Users\艾莉丝喵\Desktop\tv\tools\probe"
& $NODE .\probe.mjs help
```

Node 版本实测 v24.21.0。

二维码 PNG 用这个 Python（已装 `qrcode`，已验证 `import qrcode` 可用）：

```powershell
$PY = "C:\Users\艾莉丝喵\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
```

注意：系统 PATH 里的 `D:\Python\python.exe` **没有** `qrcode`，不要用它。

## 文件

| 路径 | 说明 |
|---|---|
| `probe.mjs` | 全部逻辑：weapi 加密、扫码双路由、trace、song/url/v1、表格与报告 |
| `cookie.json` | 登录成功后生成，保存 `MUSIC_U`、`__csrf` 等；删除即退出登录 |
| `out/qr.png` | 可选，由外部 Python `qrcode` 生成；探针不再写这个文件 |
| `out/qr-trace.json` | 每次 `qr` 覆盖：全流程响应头 trace，`set-cookie` 只留前 40 字符 + 长度 |
| `out/qr-dual.json` | 仅 `--dual` 且拿到 803 时生成：weapi→api 完整响应 + api create + NMTID 对照 + `user/account` / `login/refresh` 收尾；**含登录态** |
| `out/qr-final.json` | 仅 `--dump` 时生成：最后一次 check 原始 JSON；**含登录态** |
| `out/report.json` | `test` 的结果 |
| `out/url-<id>-<level>.json` | `url` 单次探测结果 |

`out/` 和 `cookie.json` 都在脚本所在目录，跟当前工作目录无关。

## 快速开始

```powershell
$NODE = "C:\Users\艾莉丝喵\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
$PY   = "C:\Users\艾莉丝喵\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
cd "C:\Users\艾莉丝喵\Desktop\tv\tools\probe"

# 1. 确认网络和加密链路
& $NODE .\probe.mjs selftest

# 2. 等待期只 weapi，803 后再做 api/weapi 对照（推荐用来查 803 到底带不带 cookie）
& $NODE .\probe.mjs qr --dual

# 3. 终端会打印二维码 URL，另开一个终端执行（把 <URL> 换成实际 URL）
& $PY -c "import qrcode; qrcode.make('<URL>').save('out/qr.png')"

# 4. 手机网易云音乐扫码并确认；终端看到 802 / 803 / 收尾探测结果

# 5. 登录验证通过后再跑三首 × 四档
& $NODE .\probe.mjs test
```

`qr` 默认等 300 秒；二维码过期就重新跑。`--timeout=15` 只用于验证流程。

## 子命令

### `help`

```powershell
& $NODE .\probe.mjs help
```

### `selftest`

不带登录 cookie，匿名发一次 `song/url/v1`（`2610618711` + `standard`），确认网络和 weapi 加密链路。

实测输出：

```text
[selftest] 匿名 weapi 请求（不加载 cookie.json，不带登录态）
[selftest] 目标: https://music.163.com/weapi/song/enhance/player/url/v1  id=2610618711 level=standard
[selftest] HTTP 状态: 200
[selftest] 业务 code: 200  message: -
[selftest] 单曲 code: -110  url: 空  level: -
[selftest] 耗时: 153ms
[selftest] 通过：网络可达，weapi 参数被服务端接受
```

`-110` 是匿名未登录的单曲业务码，不是加密失败。

### `qr`

```powershell
& $NODE .\probe.mjs qr                                  # 默认 weapi，300 秒
& $NODE .\probe.mjs qr --route=api --timeout=15         # 只轮询明文 api 路由，验证用
& $NODE .\probe.mjs qr --dual --timeout=300             # 等待期只 weapi；803 后再对照 api
& $NODE .\probe.mjs qr --dual --dump                    # 额外保存最后一次 check 原始 JSON
```

流程：

1. `login/qrcode/unikey` 申请 `unikey`（key/create 固定走 weapi，保证同一个 key）
2. 尝试 `login/qrcode/client/login`（`type:1`）取 `qrurl`/`qrimg`；当前服务端通常只回 801，没有 `qrimg`
3. 终端打印 `unikey` 和二维码 URL
4. **外部生成 PNG**：用上面的 `$PY` 跑 `qrcode.make(...).save('out/qr.png')`；探针不再调用任何自制编码器
5. 每 800ms 轮询 `login/qrcode/client/login`（`type:3`）：
   - 默认 `--route=weapi`：走 `/weapi/...` 加密
   - `--route=api`：走明文 `POST /api/login/qrcode/client/login?type=3`，body `key=<unikey>&csrf_token=`
   - `--dual`：**等待期只打 weapi**（与已验证能扫通的单路由一致），不碰 api 路由
6. 状态：`801` 等待扫码 → `802` 已扫码待确认 → `803` 登录成功；`800` 过期
7. 803 后写 cookie、做校验/取证（见下）

`--route` 只影响 **check** 路由；`unikey` / `create` 始终 weapi，避免拿两个不同的 key。`--dual` 会忽略 `--route`。

> 为什么 dual 等待期不再 api+weapi 双轮询：实测两条路由每 800ms 同时查同一个 key，手机扫码会直接提示失效，服务端仍停在 801。现在等待期只保留 weapi，803 之后才做 api 对照。

#### `--dual` 的 803 取证

等待期只用 weapi。收到 803 后，脚本按固定顺序：

1. a) weapi 复测一次 `type=3`
2. b) 明文 api 复测一次 `type=3`（body `key=<unikey>&csrf_token=`）
3. c) 仅当 b 的响应头 `Set-Cookie` 里含 `MUSIC_U`，再补一次明文 api `type=1`（create）
4. d) 用当前 jar 调 `user/account`
5. e) 调 `login/token/refresh`（weapi）

额外做一个 **NMTID 对照**：用 803 前 jar 里的 `NMTID`（`before`）和 803 后响应头里最新下发的 `NMTID`（`latestFromHeaders`）各打一次明文 `type=3`；`differs: true` 表示两次结果不同，cookie 会话可能绑定 NMTID。这两个请求不写回 jar。

`qr-dual.json` 结构（captures 顺序固定 weapi → api）：

```json
{
  "generatedAt": "...",
  "unikey": "...",
  "trigger": {
    "route": "weapi",
    "ts": "...",
    "status": 200,
    "headers": { "set-cookie": ["...完整值..."] },
    "bodyText": "{\"code\":803,\"message\":\"授权登陆成功\"}",
    "body": { "code": 803, "message": "授权登陆成功" },
    "code": 803
  },
  "triggerEvidence": { "headerHas": false, "bodyHas": false },
  "captures": [
    { "route": "weapi", "status": 200, "headers": {}, "bodyText": "...", "body": {}, "code": 803 },
    { "route": "api", "status": 200, "headers": {}, "bodyText": "...", "body": {}, "code": 803 }
  ],
  "apiCreate": null,
  "apiCreateSkipped": "api capture 响应头没有 MUSIC_U Set-Cookie",
  "nmtidCompare": {
    "before": "803 前的 NMTID",
    "latestFromHeaders": "803 后响应头最新 NMTID",
    "same": false,
    "skipped": null,
    "baseline": { "route": "api", "status": 200, "headers": {}, "bodyText": "...", "body": {}, "code": 801 },
    "latest": { "route": "api", "status": 200, "headers": {}, "bodyText": "...", "body": {}, "code": 200 },
    "differs": true,
    "note": "两次结果不同：cookie 会话可能绑定 NMTID"
  },
  "evidence": [
    { "route": "weapi", "headerHas": false, "bodyHas": false },
    { "route": "api", "headerHas": false, "bodyHas": false }
  ],
  "cookiesAfterCapture": {
    "names": ["NMTID"],
    "musicCookies": [],
    "hasMUSIC_U": false,
    "hasCsrf": false
  },
  "account": {
    "route": "weapi",
    "status": 200,
    "headers": {},
    "bodyText": "...",
    "body": {},
    "code": 200,
    "loggedIn": false,
    "nickname": null,
    "userId": null
  },
  "refresh": {
    "route": "weapi",
    "status": 200,
    "headers": {},
    "bodyText": "...",
    "body": {},
    "code": 301
  },
  "cookiesAfterRefresh": { "names": ["NMTID"], "musicCookies": [], "hasMUSIC_U": false, "hasCsrf": false },
  "hits": [],
  "verdict": "803 未带登录态：..."
}
```

**什么算「803 确实带 cookie」**：

- `captures[0].headers["set-cookie"]`（weapi）或 `captures[1].headers["set-cookie"]`（api）里有 `MUSIC_U=...`；或
- `captures[*].body.cookie` / `body.cookies` 里有 `MUSIC_U=...`；或
- `apiCreate.headers["set-cookie"]` / `apiCreate.body.cookie` 里有 `MUSIC_U=...`；或
- `cookiesAfterCapture.hasMUSIC_U === true`（说明 HTTP 层已经把 cookie 吸进 jar）；或
- `account.loggedIn === true`，并且 `account.body.profile.nickname` / `profile.userId` 有值；或
- `cookiesAfterRefresh.hasMUSIC_U === true`（refresh 换出了新 cookie）

对应的 `hits` 数组会列出命中项，例如：

```json
"hits": ["api.set-cookie", "api-create.set-cookie", "cookiesAfterCapture.MUSIC_U", "account.loggedIn"],
"verdict": "803 带登录态证据：api.set-cookie, api-create.set-cookie, cookiesAfterCapture.MUSIC_U, account.loggedIn"
```

如果两条 capture 的 `set-cookie` 都没有 `MUSIC_U`、`body.cookie` 也没有、`apiCreate` 被跳过、`cookiesAfterCapture` 只有 `NMTID`、`account.loggedIn=false`，那 `hits` 为空，`verdict` 是：

```text
803 未带登录态：trigger/两条 capture 的响应头和 body 都没有 MUSIC_U，cookie 快照没有 MUSIC_U，user/account 未登录
```

这就是「803 确实没有下发登录态」的负证据。

`nmtidCompare.differs` 不是 cookie 证据，而是会话绑定线索：如果 `differs: true`，说明同一个 key 用不同 NMTID 查询得到不同结果，cookie 会话很可能绑 NMTID。

`qr-dual.json` 里的 `headers` 和 `bodyText` 是**完整原文**，可能直接包含 `MUSIC_U`；不要外传。

#### `--dump`

把最后一次 `qr/check` 的原始 JSON 覆盖写到 `out/qr-final.json`，用于调试。该文件在 803 后可能含登录态，不要外传；不会自动删除。

#### 状态文案

- `800` 二维码已过期，重新运行 `qr` 生成新码
- `801` 等待扫码
- `802` 已扫码，请在手机上确认登录
- `803` 登录成功

### `url`

```powershell
& $NODE .\probe.mjs url 2610618711
& $NODE .\probe.mjs url 2610618711 --level=lossless
& $NODE .\probe.mjs url 2610618711 --real-ip=1.2.3.4
```

有 `cookie.json` 就用登录态，没有就匿名。打印 HTTP 状态、外层 `code`、单曲 `songCode`、url 有无、`br`、`size`、`freeTrialInfo`、`level`、`encodeType`、`md5`、判定，并写 `out/url-<id>-<level>.json`。行为未改。

### `test`

```powershell
& $NODE .\probe.mjs test
```

- 仍要求 `cookie.json` 里有 `MUSIC_U`；没有会退出并提示先 `qr`
- 先调 `user/account` 校验；无效会中止
- 三首 × 四档共 12 次请求，生成 `out/report.json` 和终端表格
- **如果 `--dual` 发现登录态藏在 `MUSIC_A` 等别的 cookie 名里，当前 `test` 仍会拒绝**（本次不改 `test` 行为）。这种情况先看 `qr-dual.json` 的 `cookiesAfterCapture.musicCookies`

## 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--timeout=秒` | `300` | `qr` 等待扫码上限，最大 1800 |
| `--route=weapi\|api` | `weapi` | `qr` 的 check 路由；api 是明文 `/api/...` |
| `--dual` | 关闭 | `qr` 等待期只 weapi；803 后做 api/weapi 对照并写 `out/qr-dual.json` |
| `--dump` | 关闭 | `qr` 保存最后一次 check 原始 JSON 到 `out/qr-final.json`（含登录态） |
| `--level=档位` | `exhigh` | `url` 的音质档位 |
| `--real-ip=IP` | 不发 | 可选 `X-Real-IP`；也接受 `--x-real-ip` |

所有请求默认带：

```text
User-Agent: Chrome/124 (Windows)
Referer: https://music.163.com/
Origin: https://music.163.com
Cookie: NMTID=<随机 32 hex>; ...
```

## `out/qr-trace.json`

每次 `qr` 覆盖，数组，记录 key/create/check/dual-capture/api-create/nmtid-compare/account/refresh。check 只记录状态变化（含第一次）。每条：

```json
{
  "step": "check",
  "route": "api",
  "ts": "2026-11-08T06:04:13.136Z",
  "status": 200,
  "headers": {
    "set-cookie": [
      { "preview": "NMTID=8f3...", "length": 123 }
    ],
    "content-type": "application/json;charset=UTF-8"
  },
  "bodyKeys": ["code", "message"],
  "code": 801
}
```

- 其它响应头保留全量
- `set-cookie` 只保留前 40 字符 + 完整长度（`preview` / `length`）
- 网络错误会有 `status: null`、`code: null`、`error`

## `cookie.json`

位置：`tools/probe/cookie.json`

```json
{
  "savedAt": "...",
  "cookies": {
    "MUSIC_U": "...",
    "__csrf": "...",
    "NMTID": "..."
  }
}
```

- 只由 `qr` 写入，不写死账号/cookie
- 803 后先解析响应体/响应头，再落盘，然后 `user/account` 校验
- 单路由 `qr` 校验失败：会**恢复运行前的 `cookie.json`（如果有）**；如果运行前没有，则清理本次写入的坏 cookie。这样不会因为一次失败的扫码覆盖掉已有的可用登录态
- 网络失败会保留 cookie 并提示稍后用 `test` 确认
- 当前工作区已有的 `cookie.json` 只有 `MUSIC_U` / `NMTID`，没有 `__csrf`；实测 `test` 仍 12/12 成功。`__csrf` 主要影响部分写操作，不是 `song/url/v1` 的硬要求
- 退出登录：删除 `cookie.json`；`out/qr-dual.json` / `out/qr-final.json` 也可能含登录态，一并删除

```powershell
Remove-Item "C:\Users\艾莉丝喵\Desktop\tv\tools\probe\cookie.json"
```

## 超时、重试、错误处理

- 单次 HTTP 请求超时 15 秒
- 网络错误 / 429 / 5xx 最多重试 1 次（600ms）
- QR 轮询每 800ms 一轮；`--dual` 等待期只打 weapi，803 后才 api 对照；总时长由 `--timeout` 控制
- 连续 10 次轮询网络错误会提前停止
- `url` / `test` 单次失败不会崩，继续下一条
- 退出码：0 正常；1 请求/登录失败、二维码过期/超时；2 参数错误

## 验证记录（本次改造）

- `qr --route=api --timeout=15`：成功申请 key、打印 URL、明文 api 路由返回 `801 等待扫码…`，15 秒后按预期超时退出；`out/qr-trace.json` 有 `unikey` / `create` / `check(api)` 三条记录
- `qr --dual --timeout=15`：等待期**只出现 weapi 的 801**，没有任何 api 801；按预期超时；trace 有 3 条记录（unikey、create、check weapi）；无人扫码时不生成 `qr-dual.json`
- 803 路径用构造响应做单元验证：captures 顺序固定 weapi → api；负例（803 无 MUSIC_U）→ `apiCreate` 跳过、`nmtidCompare` 正常工作、`hits` 为空、verdict 为未带登录态；正例（api capture `set-cookie` 带 MUSIC_U）→ 补 api type=1、`differs=true` 的 NMTID 对照、`evidence`、`hits`、`cookie.json` 都正确；无 NMTID 时对照跳过；临时测试脚本已删除
- 外部 Python `qrcode` 生成 PNG 已验证（370×370，Pillow 打开/verify 通过），测试文件已删除
- `selftest`：HTTP 200 / 外层 code 200；匿名单曲 `-110`（预期）
- 现有 `cookie.json`（有 `MUSIC_U` 和 `NMTID`，没有 `__csrf`）实测 `test`：三首 × 四档 12/12 全部有地址，三首均「可播」；该文件保持原样
- `test` 无 cookie 时仍干净退出并提示先 `qr`；`url` 行为未改
- 本次没有真实 803 样本（无法替用户扫码），所以 `qr-dual.json` 的真实内容要等用户扫码后生成

## 下一步

1. 运行 `& $NODE .\probe.mjs qr --dual`（默认 300 秒）
2. 复制终端打印的二维码 URL，另开终端用 `$PY` 生成 `out/qr.png`
3. 手机网易云音乐扫码并确认
4. 观察终端：`802` → `803` → weapi 复测 → api 复测 → api type=1（条件触发）→ NMTID 对照 → `user/account` → `login/refresh` → 结论
5. 看 `out/qr-dual.json`：
   - `captures[*].headers["set-cookie"]` 是否有 `MUSIC_U`
   - `captures[*].body.cookie` 是否有 `MUSIC_U`
   - `apiCreate` / `apiCreateSkipped`
   - `nmtidCompare.differs` / `note`
   - `cookiesAfterCapture.hasMUSIC_U`
   - `account.loggedIn` / `profile.nickname` / `profile.userId`
   - `refresh` 是否换出 cookie
   - `hits` / `verdict`
6. 如果 `account.loggedIn=true` 且 `cookie.json` 里有 `MUSIC_U`，再跑 `test`；如果登录态在 `MUSIC_A` 等别的 cookie 名里，先不要跑 `test`，把 `qr-dual.json` 脱敏后交回分析

如果 `qr --dual` 一直 801 到超时，先重新跑一次再扫；二维码本身用外部 Python 生成，不再使用探针里的旧编码器。
