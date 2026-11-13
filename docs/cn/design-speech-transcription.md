# 语音转写（Speech-to-Text）设计

> 实现状态（2026-11-13）：服务端 `POST /api/speech/transcriptions`、`GET /api/speech/models`、
> `POST /api/speech/synthesis`、`GET /api/speech/synthesis/models`；CLI `core-ai-cli speech
> transcribe|synthesize|models` 与 `transcribe_audio` builtin；桌面 composer 麦克风按钮；网关
> `audio.transcriptions` / `audio.speech` 端点，支持 OpenAI、Azure OpenAI、Google Cloud Speech 与
> Azure Speech 回退；默认模型在 System Settings 里按方向配置。单测覆盖客户端 multipart、路由/回退、
> 控制器解析和 WAV 编码。

## 1. 目标与非目标

**目标**

- CLI、桌面和 server 共用一条语音转写链路：客户端只上传音频，模型选择、凭据和上游调用都在 server。
- 复用既有的网关模型/凭据体系：管理员照常注册 provider 和 model，只是 endpoint 多了
  `audio.transcriptions`。
- Azure Speech 作为开箱即用的回退：server 已配置 `azureSpeechKey` / `azureSpeechRegion` 时，
  `/api/speech/models` 自动出现 `azure-speech`，无需额外注册模型。
- 桌面提供“按住说话”式的语音输入：录音、转写、把文字插入 composer 草稿。

**非目标（v1）**

- 不做实时流式字幕（websocket/SSE 增量 partial）。v1 是“录完一段再转写”。
- 不做说话人分离（diarization）和词级时间轴的前端展示；协议保留 `segments` 字段，便于后续扩展。
- 不做音频计量/计费。现有 `audio` SPI 没有 usage 概念，按字符/秒计费需要单独设计，和
  `design-media-cost-recording.md` 中的 TTS/ASR 计量项一起做。
- 不改 Web 控制台现有的 Azure Speech SDK 实时识别；它继续走 `/api/speech/token`。

## 2. 总体结构

```
CLI speech transcribe ─┐
Desktop mic button ────┼─ multipart audio ──► POST /api/speech/transcriptions
Agent transcribe_audio ┘                         │
                                                 ▼
                                      SpeechTranscriptionService
                                       │                    │
                         model = 网关模型              model = azure-speech
                                       │                    │
                                       ▼                    ▼
                     GatewayTranscriptionProvider     AudioProvider
                       (OpenAI /audio/transcriptions)   (Azure Speech fast transcription)
```

### 2.1 服务端

- `GatewayEndpointType.AUDIO_TRANSCRIPTION`（id `audio.transcriptions`）把音频模型和图片/视频模型
  区分开；`GatewayModelDiscoveryService` 会把 `mode=audio_transcription` 的模型识别成该端点，
  忽略 `audio_speech`（TTS）。
- `GatewayTranscriptionProvider` 用 `GatewayRoutingEngine.route(model, AUDIO_TRANSCRIPTION)` 选
  provider，再按 provider 类型构造 `OpenAIAudioTranscriptionClient`：
  - OpenAI / LiteLLM / openai-compatible：`{baseUrl}/audio/transcriptions`，`Authorization: Bearer`。
    Whisper 用 `verbose_json` 取分段；模型名含 `diarize` 时用 `diarized_json` 取说话人标签。
  - Azure OpenAI：`{resource}/openai/deployments/{model}/audio/transcriptions?api-version=...`，
    `api-key` 头；部署名即模型名，body 不再重复 `model`。
  - 允许复用 `OPENAI_IMAGES` / `OPENAI_COMPATIBLE` 协议（同一 baseUrl/API key 同时服务图像和音频），
    也接受显式的 `OPENAI_AUDIO`。
- `SpeechTranscriptionService` 负责“模型目录 + 回退”：
  - `/api/speech/models` 返回网关中显式声明 `audio.transcriptions` 的模型（按优先级），再追加
    `azure-speech`（仅当 Azure Speech key/region 已配置）。
  - 请求不带 `model` 时取列表第一个；列表为空且 Azure 未配置则返回
    `SPEECH_NOT_CONFIGURED`，错误信息明确告诉管理员配置哪一种。
  - Azure fallback 的 fast transcription 请求固定开启 `diarization`（`maxSpeakers=8`），
    每个 phrase 的 `speaker` 映射到 `segments[].speaker`；Azure 返回数字标签，OpenAI
    diarization 返回字母标签，统一按字符串输出。
- `SpeechTranscriptionController` 解析 multipart：字段 `audio`（兼容任意单文件字段）、`model`、
  `language`、`prompt`；限制 9 MB（core-ng 默认 10 MB 请求体上限，含 multipart 开销）；通过
  `GatewayCallAuditService` 记录 `kind=media`、
  `operation=transcribe_audio` 的审计行（参数只记模型/语言/字节数，不记音频内容）。

### 2.2 协议

`POST /api/speech/transcriptions`（`multipart/form-data`）：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `audio` | 是 | wav / mp3 / m4a / ogg / webm / flac，≤ 9 MB |
| `model` | 否 | `/api/speech/models` 返回的 `modelId`；省略用默认 |
| `language` | 否 | BCP-47（`zh-CN`）或 ISO-639-1（`zh`）；省略时网关模型自动识别，Azure 回退默认 `zh-CN` |
| `prompt` | 否 | Whisper 的 initial prompt，用来提示人名/术语 |

响应：

```json
{
  "text": "今天讨论了三件事……",
  "language": "zh-CN",
  "durationSeconds": 12.4,
  "model": "whisper-1",
  "provider": "azure-openai",
   "segments": [{ "text": "今天讨论了三件事", "speaker": "1", "start": 0.0, "end": 2.1 }]
 }
 ```

`segments` 可能为空：`gpt-4o-transcribe` 一类模型只返回文本；Whisper 的 `verbose_json` 和
Azure fast transcription 会带时间信息。`speaker` 仅在 provider 开启说话人分离时出现
（Azure fast transcription、`*-diarize` 模型）。

`POST /api/speech/synthesis`（`application/json`）：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `text` | 是 | 要合成的文本 |
| `model` | 否 | `/api/speech/synthesis/models` 返回的 `modelId`；省略用 System Settings 默认 |
| `voice` | 否 | provider voice（如 `alloy`、`zh-CN-XiaoxiaoNeural`）；省略用 provider 默认 |
| `language` | 否 | BCP-47；Azure 回退时用来选默认 voice |
| `format` | 否 | `mp3` / `wav` / `opus` 等；默认 `mp3` |
| `speed` | 否 | 语速倍率 |
| `instructions` | 否 | 支持 `gpt-4o-*-tts` 一类模型的情感/语气指令 |

响应是音频二进制，模型、provider、voice 放在 `x-speech-model` / `x-speech-provider` /
`x-speech-voice` 响应头，调用方不需要解析 body 就能命名文件。

### 2.3 CLI

- `core-ai-cli speech models`：打印当前 server 可用的转写模型和默认项。
- `core-ai-cli speech transcribe <file> [--language] [--model] [--prompt] [--format] [--output] [--json]`：
  `--format text`（默认）输出全文；`--format segments` 输出 `[HH:MM:SS] Speaker N: ...`；
  `--format json` 输出完整响应（等同 `--json`）。文本走 stdout，模型/语言/时长走 stderr，
  便于 `> notes.txt` 这类管道。
- `transcribe_audio` builtin：agent 在 CLI/桌面里直接转写本地音频；`timestamps:true` 输出
  `[mm:ss] Speaker N: ...` 行。它只在已登录时注册，和 `understand_video` 一样依赖 server 凭据。
- `SpeechClient` 是唯一客户端实现，命令和 builtin 共用。

### 2.4 桌面

- composer 文本框右侧增加麦克风按钮；`state.voiceReady` 由引擎的 `speech/models` 决定，
  未登录/未配置时按钮禁用并给出原因。
- 录音使用 Web Audio `getUserMedia` + `ScriptProcessor` 收集 PCM，`OfflineAudioContext` 重采样到
  16 kHz 单声道，再编码 WAV（44 字节头 + 16-bit PCM）。选 WAV 而不是 `MediaRecorder` 的 webm/opus，
  是因为 Azure Speech 和 OpenAI 都稳定接受 WAV，避免容器/编解码差异。
- 停止录音后 renderer 把 base64 音频交给引擎 RPC `speech/transcribe`；引擎用 `AuthConfig`
  的凭据调用同一个 server 端点，成功后把文本插到光标处。凭据不进入 renderer。
- 最长 4 分钟自动停止（16 kHz 单声道 WAV 约 7.7 MB，低于 9 MB 上限）。

## 3. 配置方式

**方式 A：网关音频模型（推荐用于 OpenAI/Azure OpenAI/Google Cloud Speech）**

1. 在 Gateway Providers 里新建或复用 OpenAI 兼容 provider；`mediaProtocol` 可留 Automatic（openai/azure
   类型会自动识别）或显式选 `OPENAI_AUDIO`。Google Cloud Speech 选 `Google Cloud Speech` provider，
   填 API key 或 service account；`mediaProtocol` 选 `GOOGLE_SPEECH`。
2. 在 Models 里新建模型，如 `modelId=whisper-1`、`upstreamModel=whisper-1`，`endpointTypes` 勾选
   **Speech to Text**（`audio.transcriptions`）；TTS 模型勾 **Text to Speech**（`audio.speech`）。
3. 在 System Settings → Media & Tools 的 **Speech to text model** / **Text to speech model**
   选择默认模型；留空时按优先级取该端点的第一个模型。默认值按每次请求实时解析，改网关模型无需重启。

**方式 B：Azure Speech 回退**

1. 在 System Settings 里填写 Azure Speech key 和 region（与现有 Web 控制台实时识别共用）。
2. 不需要注册网关模型；`/api/speech/models` 会自动返回 `azure-speech`。

两者都没有时，接口返回 `SPEECH_NOT_CONFIGURED`。

## 4. 关键决策与理由

| 决策 | 理由 | 代价 |
| --- | --- | --- |
| 服务端统一转写，而不是客户端直连 provider | API key 不下发；模型目录、回退、审计集中在一处；CLI/桌面/未来 Web 行为一致 | 多一次上传，长音频受 server 超时/带宽影响 |
| 网关模型优先，Azure Speech 回退 | 已有 Azure Speech 配置可立即使用；有网关音频模型时可用更好的模型/计费体系 | 两个上游协议需要各自维护 |
| WAV 16 kHz 单声道 | 体积可控（约 32 KB/s），所有 provider 接受，避免 webm 兼容问题 | 桌面端要做一次重采样 |
| v1 不做流式 | 实现和错误处理简单，先覆盖“语音输入/会议转写” | 长段落的等待时间等于整段时长 |

## 5. 失败模式

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `SPEECH_NOT_CONFIGURED` | 没有 `audio.transcriptions` 模型，且 Azure key/region 未配置 | 管理员在网关注册模型或配置 Azure Speech |
| `gateway model does not support endpoint` | 模型存在但没勾 `audio.transcriptions` | 在模型端点里补勾 |
| 上游 400/415 | 音频格式/大小/模型不支持 | 错误体原样返回；客户端换格式或换模型 |
| 桌面按钮灰 | 未登录 / 没有可用模型 | tooltip 给出原因 |
| 录音无声音 | 系统麦克风权限/设备问题 | renderer 捕获 `getUserMedia` 错误并 toast |

## 6. 后续工作

1. **流式 partial**：桌面按 1–2 秒切片上传，服务端做重叠去重；或接入 provider 的 realtime API。
2. **计量**：按音频秒数记录 `hub_calls` / media cost，和 TTS 一起设计。
3. **分离与词级时间轴**：把 `segments` 扩展为 `words`，供会议纪要/字幕使用。
4. **Web 控制台**：把现有浏览器 SDK 识别切换到本端点，作为 SDK 不可用时的回退。

## 7. 验证

- `:core-ai:test --tests "ai.core.media.audio.*" --tests "ai.core.tool.tools.TranscribeAudioToolTest"`
- `:core-ai-server:test --tests "ai.core.server.speech.*" --tests "ai.core.server.gateway.GatewayModelDiscoveryServiceTest"`
- `:core-ai-cli:test --tests "ai.core.cli.speech.*"`
- `npm --prefix core-ai-desktop test`（含 `voice.test.mjs`）
- 手工：`core-ai-cli speech models` → `speech transcribe <wav>`；桌面点麦克风说一句话，文本出现在草稿。
