# TVMirror

Android 手机 → Android 电视的**局域网整屏镜像**。无广告、无云中转、不做二次转码：
手机硬编码，电视硬解码，全程只走你家路由。

## 为什么做这个

电视自带的投屏（以及市面上的投屏 App）有两条老路：
一是 Miracast，延迟高且被电视固件锁在系统进程里，第三方 App 拿不到；二是经服务器的中转投屏，画质被压、还夹广告。
TVMirror 走第三条路：**自己采集、自己编码、自己传**，参数和时间全部握在手里。

## 结构

```
protocol/   纯 Java，无 Android 依赖：帧格式、握手、预设、编码降级链、自适应码率
tv/         电视端：监听 TCP、握手校验、MediaCodec 解码 → SurfaceView（按比例摆放，不拉伸）
phone/      手机端：MediaProjection 采集 → MediaCodec 编码 → TCP 发送
tools/      联调工具：协议探针、用 ffmpeg 生成测试流推给电视
docs/PLAN.md 设计文档（延迟预算、协议、失败路径）
```

## 构建

需要 JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）。

```powershell
. C:\Users\autod\Android\tvmirror-env.ps1
gradle :protocol:test                 # 53 个单测
gradle :tv:assembleRelease :phone:assembleRelease
```

产物：
- `tv/build/outputs/apk/release/tv-release.apk`
- `phone/build/outputs/apk/release/phone-release.apk`

签名材料在 `signing/`（已加入 .gitignore，不入库）。

## 安装

电视（adb）：

```powershell
adb connect <电视IP>:5555
adb install tv-release.apk
adb shell am start -n app.tvmirror.tv/.MirrorActivity
```

手机：直接安装 `phone-release.apk`。

## 使用

1. 电视打开 TVMirror，待机页显示 **本机地址** 和 **4 位配对码**。
2. 手机打开 TVMirror，点「搜索电视」（找不到就手填电视 IP）。
3. 输入电视上的配对码，点「开始投屏」（分辨率/帧率/码率上限可在投屏中实时切换）。
4. 允许录屏权限（以及录音权限，用于把声音一起投过去）。

电视端遥控器：**返回** 结束投屏，**菜单** 显示/隐藏实时统计。

统计浮层字段：`分辨率`、`帧率`（实测接收帧率，静止时会很低）、`码率`（实测接收码率）、
`端到端`（手机采集 → 电视上屏）、`解码`（送入解码器 → 上屏）、`网络`（RTT）、`丢`（丢帧数）。

## 自适应模式（唯一模式）

界面上不再提供模式选择。内部固定以「稳定性优先」为基线，并叠加自适应：

| 项 | 值 |
|---|---|
| 帧率 | 30（可在界面选 60 / 不限） |
| 码率 | 8 Mbps CBR（界面可选 1–20 Mbps） |
| I 帧间隔 | 1 秒 |
| 降码率触发 | RTT > 150ms 或 解码丢帧率 > 2%，连续 3 个采样 |
| 降幅 / 恢复 | ×0.85 / 健康 5 秒后 +20% |
| 下限 | 4 Mbps（若上限更低则跟随上限） |

> 早先的「延迟优先 / 画质优先」两档已收起：实测这台电视在 60fps 与高码率下会丢帧，
> 丢帧后触发关键帧补偿反而更卡，只有 30fps + 8 Mbps 这一档稳定。
> 开关见 `CastActivity.SHOW_PRESET_SELECTOR`。

## 编码

目前**锁定 H.264**（`CastActivity.SHOW_CODEC_SELECTOR = false`），原因见「已知限制」。

编码器能力探测（`EncoderCaps`）与降级链仍在，只是想放开 HEVC 时改一个常量即可。
码率控制用 CBR；曾尝试 CQ（恒定质量）——这台设备的编码器接受该模式，但质量旋钮基本无效，
反而让帧率失控到 100fps 并开始丢帧，因此关闭（`ScreenEncoder.USE_CQ = false`）。
AVC 使用 High profile，但不指定 Level（`Level 4.2` 对 2880×1800 是非法组合，会让电视解码器直接报错）。

## 协议

握手一行 JSON（`\n` 结尾），之后是带 4 字节长度前缀的二进制帧：

```
[0..3]  uint32 BE  长度（类型+时间戳+负载）
[4]     uint8      类型：1 视频 2 请求关键帧 3 PING 4 PONG 5 编码配置 6 音频 7 BYE 8 尺寸变更
[5..12] int64 BE   采集时间戳（微秒）
[13..]  byte[]     负载
```

视频帧原样转发编码器的输出（含 codec-config），不做 NAL 解析——
解码器吃它自己编码器吐的格式，AVC/HEVC 共用一条通路。

端口分配：

| 端口 | 用途 |
|---|---|
| TCP 27183 | 主连接：JSON 握手 + 视频帧 + 心跳 |
| TCP 27185 | 音频独立连接（PCM），避免被大关键帧堵住造成卡顿 |
| UDP 27184 | 局域网发现（电视广播自身信息） |

心跳回包（PONG）负载 16 字节：8 字节回显 + 4 字节丢帧数 + 4 字节解码帧数，
手机端据此计算丢帧率并驱动自适应码率；PING 负载 4 字节，携带手机测得的上次 RTT。

## 已知限制

- **不支持 Miracast 接收**。电视的 Wi-Fi Display 在 vendor HAL 里，框架没有公开服务，第三方 App 拿不到码流。这是本文档开头说的那个「锁」。TVMirror 用自建通道绕开它，代价是手机端也要装 App。
- **竖屏手机的画面会居中显示、两侧留黑边**。这是刻意保持比例的结果；如果铺满 16:9 会把人脸和文字拉扁。想要「转 90° 铺满」需要换 TextureView 渲染，属于后续可选增强。
- **部分 App 禁止被录声音**（如某些版权保护内容），此时只能无声投屏，画面不受影响。
- **AV1/VP9 编码**在手机上基本不存在硬件实现，实时软编达不到 1080p60。
- 电视端统计浮层里的「端到端」依赖跨设备时钟换算：手机把编码器 PTS 从 `CLOCK_MONOTONIC`
  换算到 `CLOCK_BOOTTIME`，再由心跳估算时差。静态画面下该值会显得很大（显示的是最后一帧的年龄），
  运动中才有参考意义。
- **超过 1080p 的分辨率在这台电视上不可用**。`OMX.MS.AVC.Decoder` 声称支持 4096×2304，
  实测喂 2880×1800 会在收到配置帧时报 `ERROR(0x90000001)`，能力表与真实上限不一致。
  因此「自动」档停在 1080p 长边（`CastActivity.AUTO_NATIVE_CAP`）。
- **实测码率由画面内容决定，不是设定值**。这颗编码器把 `bit_rate` 当上限：缓移镜头只有
  0.5–1.8 Mbps，即使上限设 8 Mbps。改 CBR/VBR/CQ、改 profile 都不改变这一点。
- **H.265 在 1080p 运动中会花屏**，1080p60 会闪黑（因此锁定 H.264）。
- 首次连接必须输入配对码；配对码存在电视本地，卸载 App 才会重置。

## 几个实现上的坑（都已在代码里注释）

- **一个 MediaProjection 只能 create 一次 VirtualDisplay**。旋转屏时重建会抛异常并把投影终止，表现是「打开相机/转屏后投屏自动断」。正确做法是复用同一个 VirtualDisplay，用 `setSurface()` + `resize()`。
- **编码器输出原样透传，不要自作聪明转 Annex-B**。曾按「AVCC 必须转 Annex-B」的常识做了转换，结果运动画面花屏；实测这台设备的输出本来就被解码器接受，回退后滚动、相机等动态场景才干净。
- **不要为了限帧去丢编码后的 P 帧**。H.264 帧间预测依赖前序帧，丢一帧会让后续整段花屏；限帧要靠编码器/采集端参数，不能靠丢包。
- **120Hz 屏幕会让 VirtualDisplay 按刷新率出帧**，编码器的帧率只是提示（实测编码器日志仍报 60fps）。
- 部分电视固件**拒绝第三方 App 拉起前台服务**，`startForeground` 失败不能让它打断 `onCreate`，否则端口监听永远不会启动。

## 测试

协议层有 53 个 JVM 单测，覆盖帧头位级正确性、握手校验、预设参数、降级链、自适应状态机：

```
gradle :protocol:test
```

真机联调工具：

```
python tools/probe_tv.py <电视IP> <配对码>          # 控制面：配对码/忙线/心跳
python tools/send_stream.py <电视IP> <配对码> out/test.h264 30   # 推测试码流验证解码
```
