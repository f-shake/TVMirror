# TVMirror · 开发计划

> 目标：一台 Android 手机 → 小米电视（MiTV-MFTR0，Android 14）的**低延迟整屏镜像**，
> 无广告、无云中转、局域网直连，画质与延迟可量化。

状态：阶段一产物（待闸门1 确认）。本文件不计入代码修改范围。

---

## 1. 功能需求

每条都可被一条测试用例/验收动作验证。

| ID | 需求 | 量化验收标准 |
|---|---|---|
| R1 | 手机端一键开始镜像 | 点击「开始投屏」到电视出现首帧 ≤ **3.0s**（5GHz，已配对） |
| R2 | 低延迟 | 采集时间戳 → 解码输出时间戳之差 P50 ≤ **80ms**，P95 ≤ **150ms**（1080p60） |
| R3 | 稳定 60fps | 连续投屏 30 分钟，接收端实际解码帧率 ≥ **57fps**，累计丢帧率 < **0.5%** |
| R4 | 无花屏 | 单次 30 分钟投屏中，可见花屏/绿屏次数 = **0**（由关键帧请求机制保证） |
| R5 | 零广告、零回传 | 两个 APK 内不含任何广告 SDK / 统计 SDK；除局域网外无出站连接 |
| R6 | 画质可调 | 手机端可选 720p / 1080p；码率 4 / 8 / 12 / 16 Mbps；默认 **1080p + 12Mbps** |
| R7 | 自动重连 | 网络瞬断后 ≤ **3s** 恢复画面（无需重新点按） |
| R8 | 电视端待机页 | 无连接时显示：本机 IP、端口、等待状态、已连接设备名 |
| R9 | 遥控器可操作 | 遥控器「返回」退出镜像回待机页；「菜单」键切换性能统计浮层 |
| R10 | 手机随时停止 | 手机端点停止后 ≤ **1s** 电视回到待机页 |
| R11 | 电视端常驻 | 开机自启并在后台等待连接；通过 adb 加入电池优化白名单 |
| R12 | 配对保护 | **首次**连接需输入电视上显示的 4 位配对码；配对成功后本机记住，后续免输 |
| R13 | 音频随投 | 手机媒体音频随画面一同播放（AudioPlaybackCapture），音画不同步 ≤ **100ms** |
| R14 | 编码可选 | 支持 AVC / HEVC / AV1 / VP9；**只列出本机真正有硬编的编码**，其余置灰并注明原因；AVC 永久可用作兜底 |
| R15 | 三档预设 | 「延迟优先 / 画质优先 / 稳定性优先」，切换后 ≤2s 按新参数重建编解码器 |

**明确不做**（防止范围蔓延）：
- 不做 Miracast 接收（已实测：`wifidisplay-hal` 在 MTK vendor 层，框架无公开服务，第三方不可接管）
- 不做 DLNA/UPnP 推送（用户本轮只要整屏镜像）
- 不做 iOS / AirPlay
- 不做反向控制（用手机控制电视焦点）——首版不做
- 不做公网穿透 / 云中转

---

## 2. 技术方案（含选型理由与否决方案）

### 2.1 选型总览

| 环节 | 选型 | 理由 |
|---|---|---|
| 采集 | `MediaProjection` + `VirtualDisplay` | Android 唯一合法的全屏采集通道，无需 root |
| 编码 | `MediaCodec` 硬编，**AVC / HEVC / AV1 / VP9 按设备能力可选**，AVC 兜底 | 硬编延迟低；编码可选让用户按机型与内容在画质/体积/兼容性间取舍（见 2.4） |
| 传输 | TCP + 4 字节长度前缀 + `TCP_NODELAY` | 局域网丢包极低，TCP 免去丢帧/花屏处理；实现复杂度最低 |
| 解码 | `MediaCodec` 异步模式 → 直接输出到 `SurfaceView` 的 Surface | 零拷贝，不经 Bitmap，延迟最低 |
| 发现 | UDP 广播（27184）TV 应答 | 免手输 IP；失败回退手动输入 |
| 音频 | `AudioPlaybackCapture`（Android 10+）→ AAC-LC | 唯一能录到其他 App 声音的官方通道 |
| 构建 | Gradle 8.9 + AGP 8.5.2 + **Java 17**（不用 Kotlin） | 两个小 App 无需 Kotlin；少一层编译器依赖，构建更稳 |
| 最低版本 | minSdk 26 / targetSdk 34 | 覆盖 Android 8+，电视是 34 |

### 2.2 被否决的方案

| 备选 | 否决理由 |
|---|---|
| **Miracast 接收（WFD sink）** | 实测电视 `service list` 无 `IWifiDisplay` 公开服务，WFD 全在 `wifidisplay-hal-1-0`；只有系统签名的 `com.xiaomi.mitv.smartshare` 能用。第三方 App 拿不到码流，物理不可行。 |
| **WebRTC** | 依赖 ~10MB、首帧慢（ICE/DTLS 握手 1–2s）、对局域网低延迟无收益，且需要 STUN 逻辑。 |
| **UDP + FEC / RTP** | 局域网丢包 <0.1%，却要自己实现重组、乱序处理、FEC 解码；收益不抵复杂度。TCP 更稳。 |
| **DLNA 推送** | 解决的是「推视频 URL」不是「镜像屏幕」，不满足本轮需求。 |
| **Kotlin + Compose** | Compose 编译慢、依赖大；本 UI 只有几个按钮和一个 SurfaceView，View 体系足够。 |
| **H.265 按固定格式硬编码** | ~~否决~~ → **采纳为可选编码**（见 2.4）。仅要求：不得假设所有机型都有 HEVC 硬编，必须运行时探测后降级。 |

### 2.3 延迟预算（「延迟优先」预设，1080p60，目标 P50 ≤ 80ms）

| 阶段 | 预算 |
|---|---|
| 屏幕合成 + MediaProjection 取帧 | 16–33ms（1–2 帧） |
| H.264 硬编 | 10–20ms |
| 局域网传输 | 2–5ms |
| H.264 硬解 | 10–20ms |
| Surface 合成上屏 | 16ms |
| **合计** | **55–95ms** |

### 2.4 编码可选：能力探测 + 一致性降级

| 编码 | 握手取值 | 手机硬编可用性 | 电视 MT9653 硬解 | 定位 | 说明 |
|---|---|---|---|---|---|
| **H.264 / AVC** | `avc` | 几乎所有机型，1080p60 普遍支持 | ✅ | **默认 + 永久兜底** | 兼容性最好，任何情况都能回退到这里 |
| **H.265 / HEVC** | `hevc` | 多数 2019+ 机型，1080p60 常见 | ✅ | 画质优先时首选 | 同码率画质约 +30–40%。专利由整机/系统层授权覆盖，自用不额外收费；UI 里标注一句说明 |
| **AV1** | `av1` | **基本无硬编**（Android 14 手机端以解码为主） | ✅ 解码 | 仅当设备上报编码器才可选 | 软编 1080p60 跑不到实时，**不做假支持**：无编码器时置灰 + 「本机不支持 AV1 编码」 |
| **VP9** | `vp9` | 少数机型 | ✅ 解码 | 仅当上报才可选 | 同 AV1 策略 |

**探测与降级规则（必须遵守）：**

1. 启动时用 `MediaCodecList` 枚举 `video/*` 编码器，读取 `COLOR_FormatSurface` 与分辨率/帧率能力，**只把真正支持 1080p60 的列出来**；不支持的置灰并显示原因。
2. 用户选择写入握手 `codec`；TV 端按该值匹配解码器，匹配不到回 `{"ok":false,"err":"codec"}`。
3. 降级顺序固定：**用户选择 → HEVC → AVC**。AVC 找不到就直接报错，不静默。
4. 预设与编码冲突时（如画质优先默认 HEVC，但手机无 HEVC 硬编），自动回落 AVC 并在 UI 提示一次，不弹窗打断。

### 2.5 三档预设

| 参数 | 延迟优先（默认） | 画质优先 | 稳定性优先 |
|---|---|---|---|
| 分辨率 | 1080p | 1080p（支持时 1440p） | 1080p |
| 帧率 | 60 | 60 | 30 |
| 码率 | 8–12 Mbps CBR | 16–25 Mbps VBR | 6–10 Mbps CBR |
| I 帧间隔 | 1s | 2s | 1s |
| 编码偏好 | AVC | HEVC → AVC | AVC |
| 接收端缓冲 | 0 帧（收到即渲染） | 1–2 帧 | 100–200ms 抖动缓冲 |
| 自适应策略 | 激进：RTT>50ms 即降码率 | 保守：仅在解码丢帧时降 | 温和：优先降帧率、不降码率 |
| 预期延迟 | 55–95ms | 90–140ms | 150–250ms |
| 适用场景 | 游戏、演示、需要跟手 | 看片、看照片 | 弱 Wi‑Fi、老路由、多人共用 |

预设是**参数包**，不是三套代码：切换时重建编码器与解码器，握手发 `CONFIG`，电视端 2s 内切换完成。

---

## 3. 系统结构

```
┌──────────────────── 手机 (app.tvmirror.phone) ────────────────────┐
│  CastActivity ──► CaptureService (前台服务)                        │
│                      ├─ VirtualDisplay ◄── MediaProjection         │
│                      ├─ ScreenEncoder (MediaCodec surface 输入)    │
│                      ├─ AudioEncoder (AAC)                        │
│                      └─ Sender (TCP client, 帧长前缀)              │
│  Discovery ──► UDP 广播 27184 找电视                              │
└───────────────────────────────┬───────────────────────────────────┘
                                │ TCP 27183（单向视频 + 控制消息）
┌───────────────────────────────▼──── 电视 (app.tvmirror.tv) ───────┐
│  ReceiverService (前台服务)                                        │
│    ├─ DiscoveryResponder (UDP 27184 应答本机 IP:27183)            │
│    ├─ Session (握手 / 心跳 / 关键帧请求 / 分辨率切换)              │
│    ├─ VideoDecoder (MediaCodec async → Surface)                    │
│    └─ StatsCollector (fps / kbps / rtt / 丢帧)                     │
│  MirrorActivity (SurfaceView + 待机页 + 统计浮层)                  │
└───────────────────────────────────────────────────────────────────┘

:protocol 模块（纯 Java，无 Android 依赖）被两端共同引用，
承载握手结构、帧头常量、状态机 —— 保证两端协议不漂移。
```

---

## 4. 数据结构

### 4.1 握手（手机 → 电视，JSON，UTF-8，`\n` 结尾）

```json
{
  "v": 1,
  "codec": "avc",
  "preset": "latency",
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "bitrate": 12000000,
  "audio": false,
  "pin": "1234",
  "name": "Xiaomi 14"
}
```

| 字段 | 类型 | 可空 | 默认 | 约束 |
|---|---|---|---|---|
| `v` | int | 否 | 1 | 必须 = 1，否则拒绝 |
| `codec` | string | 否 | `"avc"` | 枚举：`avc` / `hevc` / `av1` / `vp9`，超出枚举拒绝 |
| `preset` | string | 否 | `"latency"` | 枚举：`latency` / `quality` / `stable` |
| `width`/`height` | int | 否 | 1920/1080 | 偶数，64–3840 / 64–2160 |
| `fps` | int | 否 | 60 | 24–60 |
| `bitrate` | int | 否 | 12000000 | 2_000_000–25_000_000 |
| `audio` | bool | 否 | false | — |
| `pin` | string | 是 | null | 4 位数字；未启用配对时为空 |
| `name` | string | 是 | `"Android"` | ≤ 64 字符，仅用于待机页显示 |

电视端应答：`{"ok":true,"session":<int>}` / `{"ok":false,"err":"pin"|"busy"|"version"}`

### 4.2 视频帧（手机 → 电视，二进制）

```
[0..3]  uint32 BE  payload 长度（不含本 4 字节）
[4]     uint8      type：1=VIDEO, 2=KEYFRAME_REQ, 3=PING, 4=PONG, 5=CONFIG, 6=AUDIO
[5..12] int64 BE   采集时间戳（纳秒，System.nanoTime 同源换算）
[13..]  byte[]     payload（Annex-B 码流 / 控制体）
```

- `type=2`（KEYFRAME_REQ）payload 为空，电视发现解码卡顿/丢帧时发。
- `type=5`（CONFIG）承载分辨率/码率变更，payload 为 4.1 的 JSON，用于旋转屏/切清晰度。
- `type=6`（AUDIO）payload 前 8 字节为 PTS，其后为 AAC 裸帧。

### 4.3 运行时状态（电视端 `StatsCollector`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `decodedFrames` | long | 累计解码帧数 |
| `droppedFrames` | long | 解码器丢弃数 |
| `fps` | float | 1s 滑动窗口 |
| `kbps` | float | 1s 滑动窗口 |
| `rttMs` | int | PING/PONG 往返 |
| `lastPtsUs` | long | 上一帧时间戳 |

### 4.4 自适应码率状态机（`:protocol` 内，可单测）

状态：`STEADY / CONGESTED / RECOVER`。
输入：`rttMs`、`sendQueueBytes`、`droppedFrames`、`preset`。

| 预设 | 触发降码率 | 降幅 | 恢复步长 | 恢复前提 |
|---|---|---|---|---|
| 延迟优先 | `rtt > 50ms` 或队列 > 256KB，连续 3 周期 | ×0.7 | +10% / 5s | 连续 10s 健康 |
| 画质优先 | 仅在解码丢帧 > 1% 时 | ×0.85 | +10% / 10s | 连续 20s 健康 |
| 稳定性优先 | `rtt > 120ms` 或队列 > 1MB | ×0.8，**先降帧率再降码率** | +10% / 15s | 连续 15s 健康 |

统一约束：码率下限 2Mbps、上限为用户所选档位；恢复不得快于降幅的一半（防振荡）。

---

## 5. 模块划分（职责 / 不负责什么）

| 模块 | 负责 | **不负责** |
|---|---|---|
| `protocol` | 握手编解码、帧头读写、自适应状态机 | 任何 Android API、I/O、线程 |
| `phone/CastActivity` | 权限申请、参数选择、连接状态 UI | 编码、传输 |
| `phone/CaptureService` | 前台通知、持有 MediaProjection 生命周期、拉起 VirtualDisplay | 网络收发 |
| `phone/ScreenEncoder` | 配置并驱动 MediaCodec 编码器、产出 Annex-B 帧 | 发送、UI |
| `phone/EncoderCaps` | 枚举本机可用编码器与其 1080p60 能力、给出置灰原因 | 编解码本身 |
| `protocol/Preset` | 三档预设参数包（纯数据 + 合法性校验） | Android API、I/O |
| `phone/AudioEncoder` | 采集播放音频并编 AAC | 视频链路 |
| `phone/Sender` | TCP 连接、发送帧、PING、接收控制消息 | 编解码 |
| `phone/Discovery` | UDP 广播找电视、解析应答 | 建立视频会话 |
| `tv/ReceiverService` | 监听 27183、接受会话、生命周期 | 解码细节、绘制 |
| `tv/Session` | 握手校验、PIN 校验、心跳、关键帧请求、分辨率切换 | 编解码器配置 |
| `tv/VideoDecoder` | MediaCodec 异步解码、Surface 输出、错误恢复 | 网络、UI |
| `tv/DecoderCaps` | 校验电视能否解握手要求的编码，给出降级建议 | 选择编码（由手机端决定） |
| `tv/MirrorActivity` | SurfaceView 挂载、待机页、统计浮层、遥控器按键 | socket、解码 |
| `tv/StatsCollector` | 统计计算与展示数据 | 决策（只提供数据） |

---

## 6. 交互逻辑

### 6.1 首次使用（手机端）
1. 打开 App → 请求「录屏权限」系统弹窗 → 用户允许。
2. App 后台 UDP 广播 → 电视应答 → 列表显示「客厅的小米电视」。
3. 选择设备 → 若启用配对，电视弹出/显示 4 位码 → 输入。
4. 选择**预设**（延迟/画质/稳定）与**编码**（默认已按预设预选，不可用的置灰）。
5. 握手成功 → 电视 `MirrorActivity` 切到画面模式 → 首帧 ≤ 3s。

### 6.2 电视端状态流转
```
IDLE（待机页：IP/端口/等待中）
  └─ 收到握手 ──► CONNECTING（显示"正在连接 <设备名>"）
        ├─ 校验失败 ──► IDLE（浮层提示 3s）
        └─ 成功 ──► STREAMING（全屏 SurfaceView）
              ├─ 遥控器返回 ──► IDLE
              ├─ 手机断开 ──► IDLE
              └─ 解码错误 ──► 请求 IDR ──(3 次仍失败)──► IDLE + 提示
```

### 6.3 按键映射（电视遥控器）
| 按键 | 行为 |
|---|---|
| 返回 | 结束当前会话，回待机页 |
| 菜单 | 显示/隐藏统计浮层（fps / kbps / rtt / 丢帧） |
| 方向键 | 不做任何事（避免误触） |

### 6.4 手机端停止
点击「停止投屏」→ 关闭 VirtualDisplay → 发送 `BYE` → 关闭 socket → 电视 ≤1s 回待机页。

---

## 7. 异常情况（含提示文案）

| 场景 | 表现 | 文案 |
|---|---|---|
| 用户拒绝录屏权限 | 回到参数页，不启动 | 「需要录屏权限才能开始投屏」 |
| 找不到电视 | 列表为空 + 重试按钮 | 「没找到电视，请确认手机和电视连同一个 Wi-Fi」 |
| PIN 错误 | 输入框抖动 + 清空 | 「配对码不对，请看电视上显示的 4 位数字」 |
| 电视已被占用 | 拒绝并提示 | 「电视正在被另一台设备投屏」 |
| 版本不匹配 | 拒绝 | 「手机和电视的 App 版本不一致，请更新」 |
| 编码器不支持 1080p60 | 自动降级 720p30 并提示 | 「你的手机不支持 1080p60，已降到 720p30」 |
| 所选编码本机无硬编（如 AV1） | 列表中置灰；若已选则回落 AVC | 「本机不支持 AV1 编码，已改用 H.264」 |
| 电视解不了所选编码 | 握手回 `err=codec`，手机端自动降级重连 | 「电视不支持这个编码，已自动换成 H.264」 |
| 预设切换时编码器重建失败 | 回退到上一组可用参数 | 「切换失败，已恢复原来的设置」 |
| 运行中断线 | 手机端 3s 内自动重连；失败转手动 | 「连接断开，正在重连…」/「重连失败，点击重试」 |
| 电视端解码连续失败 | 请求关键帧，3 次仍失败则断开 | 「画面解码失败，请重新投屏」 |
| 电视端服务被系统杀 | 前台服务 + 引导加入电池白名单 | 待机页提示「请在系统设置中允许后台运行」 |
| 音频采集被 App 禁止 | 静音继续投视频 | 「当前内容不允许录音，已仅投画面」 |

---

## 8. 边界条件

| 条件 | 处理 |
|---|---|
| 手机横竖屏切换 | 发 `CONFIG` 重建编码器；电视端重建解码器，期间保持最后一帧 |
| 手机分辨率 > 电视（如 2K） | 按电视面板 3840 上限等比缩放到 ≤1920 宽 |
| 电视是 4K 面板 | SurfaceView 全屏，解码输出 1080p 由系统上采样 |
| 首次运行无任何缓存 | 不做「最近设备」自动重连，必须显式选择（避免误投） |
| 空/非法 IP 手输 | 输入框实时校验，非法时按钮禁用 |
| 两台手机同时连接 | 第二台收到 `busy`，电视端不切换 |
| 投屏中切换预设/编码 | 发 `CONFIG`，两端重建编解码器；期间保持最后一帧，不黑屏 |
| 所选编码在电视端不存在 | 按 2.4 降级链回落，最多重试 1 次，仍失败则报错退出 |
| 手机端息屏 | 投屏期间持有 WakeLock + 前台服务，保持采集 |
| 电视端待机（HDMI 关） | 不特殊处理，仅记录到日志 |

---

## 9. 修改范围（文件清单）

全部为**新增**文件，不修改 `Documents\GitHub` 下任何现有项目。

```
TVMirror/
├─ README.md
├─ .gitignore
├─ settings.gradle
├─ build.gradle
├─ gradle.properties
├─ gradlew / gradlew.bat / gradle/wrapper/*          （Gradle wrapper）
├─ docs/PLAN.md                                      （本文件，阶段一产物）
├─ protocol/
│  ├─ build.gradle
│  └─ src/main/java/app/tvmirror/protocol/
│     ├─ Handshake.java
│     ├─ FrameHeader.java
│     ├─ FrameType.java
│     ├─ AdaptiveBitrate.java
│     └─ Preset.java
│  └─ src/test/java/app/tvmirror/protocol/
│     ├─ HandshakeTest.java
│     ├─ FrameHeaderTest.java
│     ├─ AdaptiveBitrateTest.java
│     ├─ PresetTest.java
│     └─ CodecFallbackTest.java
├─ tv/
│  ├─ build.gradle
│  ├─ src/main/AndroidManifest.xml
│  ├─ src/main/java/app/tvmirror/tv/
│  │  ├─ MirrorActivity.java
│  │  ├─ ReceiverService.java
│  │  ├─ Session.java
│  │  ├─ VideoDecoder.java
│  │  ├─ AudioDecoder.java
│  │  ├─ DecoderCaps.java
│  │  ├─ BootReceiver.java
│  │  ├─ DiscoveryResponder.java
│  │  └─ StatsCollector.java
│  └─ src/main/res/
│     ├─ layout/activity_mirror.xml
│     ├─ values/strings.xml
│     ├─ values/themes.xml
│     └─ mipmap-*/ic_launcher.png（或 adaptive icon xml）
└─ phone/
   ├─ build.gradle
   ├─ src/main/AndroidManifest.xml
   ├─ src/main/java/app/tvmirror/phone/
   │  ├─ CastActivity.java
   │  ├─ CaptureService.java
   │  ├─ ScreenEncoder.java
   │  ├─ EncoderCaps.java          （编码能力探测 + 置灰原因）
   │  ├─ AudioEncoder.java         （AudioPlaybackCapture → AAC-LC，必做）
   │  ├─ Sender.java
   │  └─ Discovery.java
   └─ src/main/res/...
```

---

## 10. 实现步骤

每步都能独立验证，前一步不过不进入下一步。

| # | 步骤 | 验证方式 |
|---|---|---|
| S0 | 安装 JDK 17 + Android cmdline-tools + `platforms;android-34` + `build-tools;34.0.0` | `sdkmanager --list_installed`、`javac -version` |
| S1 | 建 Gradle 多模块骨架，`:protocol` 落 5 个类（含 `Preset`） | `gradlew :protocol:test` 通过 |
| S2 | TV 端：Manifest + Activity（待机页）+ 前台服务 + 监听端口 | `adb install` 成功，`adb shell am start` 打开待机页 |
| S3 | TV 端：解码器 + SurfaceView 能解本地码流（用手机端跑通前先用测试码流） | 电视上能播出画面 |
| S4 | 手机端：权限 + VirtualDisplay + 编码器（先固定 AVC），帧先落日志 | logcat 里能看到帧时间戳递增 |
| S5 | 打通 TCP：手机发帧 → 电视解码显示 | 真机首帧出现 |
| S6 | 发现 + 握手 + PIN + busy 拒绝 | 两端联调三种失败路径 |
| S7 | **编码可选**：`EncoderCaps`/`DecoderCaps` + 降级链（user→HEVC→AVC） | 手机端只能选到真实支持的编码；强制选不支持的会被拒 |
| S8 | **三档预设**参数包 + 投屏中切换（发 `CONFIG` 重建编解码器） | 三种预设各跑一次，参数与 2.5 表一致 |
| S9 | 统计浮层 + 自适应码率（按预设阈值） | 电视上看到 fps/kbps/rtt 实时变化，切换预设阈值随之变化 |
| S10 | 重连、旋转屏、分辨率切换 | 手动拔网/转屏验证 |
| S11 | 音频：AudioPlaybackCapture 采集 → AAC → TV 端 AudioTrack 播放 | 音画同步主观 + 时间戳差 |
| S12 | 打包签名 + 安装脚本 + README | 两个 APK 可独立安装 |
| S13 | 30 分钟稳定性压测 + 延迟实测（**三种预设与至少两种编码各测一次**） | 输出统计报告 |

---

## 11. 测试方案

### 11.1 单元测试（JVM，无需设备）
- `HandshakeTest`：JSON 序列化/反序列化、缺字段默认值、非法版本、超长 name、非法分辨率
- `FrameHeaderTest`：长度前缀读写、大端序、超长帧拒绝、截断帧处理、时间戳往返
- `AdaptiveBitrateTest`：拥塞降码率、恢复升码率、上下限钳制、连续采样去抖、**三档预设各自阈值**
- `PresetTest`：参数包合法性、非法值拒绝、预设 × 编码组合的默认值
- `CodecFallbackTest`：`user → hevc → avc` 降级链、全部不可用时报错（纯逻辑，不依赖设备）

落在 `protocol/src/test/java/`，运行：`gradlew :protocol:test`。

### 11.2 端到端（真机）
电视：`192.168.0.88`，通过 adb 安装；手机：用户设备。
覆盖：首次连接、PIN 错误、断线重连、旋转屏、两台设备争抢、30 分钟连续。

### 11.3 性能测试
- 延迟：帧头 PTS → 解码输出时间戳差，统计 P50/P95
- 帧率：统计浮层 + `dumpsys gfxinfo` 辅助
- 稳定性：30 分钟不掉线、不花屏

> 说明：MediaCodec 相关逻辑无法在 JVM 单测中覆盖，由 11.2 的真机联调承担，属**已知测试盲区**，会在交付小结中显式列出。

---

## 附：已闭环决策（原未决问题）

| # | 问题 | 决定 | 影响 |
|---|---|---|---|
| Q1 | 手机机型 | **Android 12+ 机型**（具体型号待用户提供，不阻塞开发） | 以 targetSdk 34 / MediaProjection 新行为为准，兼容 Android 10+ |
| Q2 | 是否投音频 | **投**（AudioPlaybackCapture → AAC-LC，Android 10+） | 新增 `phone/AudioEncoder.java`，TV 端增加音频解码与播放链路 |
| Q3 | 电视端常驻 | **开机自启 + 后台常驻** | 新增 `BootReceiver`，并需 adb 侧做电池白名单 |
| Q4 | 配对码 | **启用 4 位码，首次配对后记住** | TV 端生成并显示；手机端存储配对结果 |
| Q5 | 手机端分发 | **下载链接安装**（不依赖数据线） | S10 增加 APK 直链交付步骤 |
| Q6 | 编码可选 | **AVC / HEVC / AV1 / VP9 运行时探测**，不支持的置灰；AV1 现阶段基本无硬编（见 2.4） | 新增 `EncoderCaps`、`DecoderCaps`；握手增加 `codec` 枚举与降级链 |
| Q7 | 预设 | **延迟优先 / 画质优先 / 稳定性优先**三档参数包 | 新增 `protocol/Preset`；握手增加 `preset`；自适应阈值按预设分化 |

### 因决策产生的计划增补
- `phone/AudioEncoder.java` 由「可选」转为**必做**；
- TV 端新增 `tv/AudioDecoder.java`、`tv/BootReceiver.java`；
- TV 端 `mirror` 模块新增音频播放通路（`AudioTrack`）；
- 新增文件：`tv/src/main/java/app/tvmirror/tv/AudioDecoder.java`、`tv/BootReceiver.java`。

**当前未决项：0。**

### 环境备注
用户提示：下载链接不通时优先尝试**系统代理**，再考虑镜像源。S0 阶段按此顺序处理。

