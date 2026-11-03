# Generations 页面视频预览：故障分析与技术方案

> 状态：设计稿（P1 核心 + 前端预览 + 热路径关联已实现，2026-11-03；实现偏差见 §17）
> 日期：2026-11-03
> 关联代码：`MediaJobContentController`、`GatewayMediaProvider`、`MediaJobService`、`Generations.tsx`
> 目标读者：实现本方案的工程师；行号仅作定位参考，以当前 master 代码为准。

---

## 0. TL;DR

**为什么看不了**

- Docker 里的 `core-ai-server` 在 2026-11-03 12:49 收到 `GET /api/media-jobs/361f2e56-7bc6-4375-b0be-6177ef3cf655/content`，返回 **500**。
- 服务端栈是 `MediaJobContentController` → `GatewayMediaProvider.downloadVideo` → `VertexGeminiOmniMediaProvider.downloadVideo`；最后一步 `GET https://aiplatform.googleapis.com/v1beta1/projects/.../interactions/{upstream_video_id}` 返回 **404 `Requested entity was not found`**。
- 这个 job 在 2026-10-25 14:04 完成，到查看时已经 9 天；Gemini/Vertex Interactions 的存储是有保留期的（Gemini API 免费层 1 天、付费层默认 55 天且可配置为 7/14/28/55 天；Vertex 预览版按 Google Cloud 的存储策略执行）。交互记录被清理后，`upstream_video_id` 就只剩一个无效指针。
- 这不是个例：库里 116 条显式 `media_type=video` 的 job（Gemini 93、KIE 20、Volcano Ark 3），加上 3 条 legacy 缺 `media_type` 的记录，**没有一条设置了 `file_id`**。也就是说所有视频预览都建立在上游资产“还在”这个假设上。

**根因**

视频生成只在 `media_jobs` 里保存 `upstream_video_id`，**没有像图片那样在生成完成时把字节落到平台文件存储**。图片在 `MediaJobService.createImageJob()` 里会立刻 `storeImage()` 写 `FileService` 并设置 `fileId`；视频只有 `storeBytes()`，但只有“视频被当成下一次生成的输入引用”时才会被调用，普通预览路径不会调用它。于是：

```
图片：生成 -> 字节存 FileService -> media_jobs.file_id -> 预览永远可用
视频：生成 -> 只存 upstream_video_id -> 预览时回源 -> 上游过期 -> 404 -> 500
```

**方案**

1. **完成时物化**：视频进入 `completed` 时，把字节下载一次并写入 `FileService`，`MediaJob` 记录 `fileId / artifactState / posterFileId`。
2. **热路径复用**：agent 调 `get_video_status` 本来就会下载并保存视频；把这次保存的 `FileRecord` 通过 `media_id` 关联回 `MediaJob`，避免二次下载。Desktop/CLI 的 `get_video_status` 会请求 `/api/gateway/v1/videos/:id/content`，服务端同样能拿到完整字节；在这个端点里“下载即归档”，把字节顺手存进 `FileService`。
3. **内容端点改造**：`/api/media-jobs/:id/content` 优先 307 到对象存储的预签名 URL（Azure/MinIO 原生支持 HTTP Range）；没有对象存储时退化为全量 200（可播放但不支持 seek，见 §6.4）；上游已过期返回 410 + 明确错误码，而不是 500。
4. **后台回填**：新增定时 Job，批量轮询未终结的 job、物化已完成但没有 `fileId` 的 job；对 404/410 标记 `expired`，不再无限重试。
5. **前端预览组件**：列表显示视频海报 + 时长；弹层用统一 `VideoPlayer`，处理 `stored / pending / expired / failed` 四种状态；默认静音自动播放，点击开启声音，避免 Chrome 自动播放策略拦截。

> 注意：当前库里 10 月 25 日及更早的视频，上游已经 404，**无法恢复**；本方案保证从现在开始生成的视频不再丢，并把历史 job 明确标记为“已过期”，不再显示一个黑屏播放器。

---

## 1. 故障现场

### 1.1 运行环境

```text
core-ai-server   chancetop/core-ai-server:latest   0.0.0.0:8080->8080, 0.0.0.0:8443->8443
core-ai-mongo    mongo:7
core-ai-redis    redis:7-alpine
```

- 对象存储已配置 Azure Blob（账号名略），`storage_provider` 为空时 `ObjectStorageServiceResolver` 默认走 Azure。
- 视频 provider：
  - Gemini：`type=gemini`，`media_protocol=VERTEX_GEMINI_INTERACTIONS`，`base_url=https://aiplatform.googleapis.com/v1beta1`，`vertex_location=global`。
  - KIE：`media_protocol=KIE`。
  - Volcano Ark：`media_protocol=VOLCENGINE_ARK`。

### 1.2 服务端日志（原样摘录）

```text
2026-11-03T12:49:25.825295859Z ERROR c.f.i.log.LogManager -
[java.lang.RuntimeException] video download failed: HTTP 404:
{"error":{"message":"Requested entity was not found.","code":"not_found"}}
    at ai.core.media.VertexGeminiOmniMediaProvider.downloadVideo(VertexGeminiOmniMediaProvider.java:100)
    at ai.core.server.gateway.GatewayMediaProvider.videoBytes(GatewayMediaProvider.java:170)
    at ai.core.server.gateway.GatewayMediaProvider.downloadVideo(GatewayMediaProvider.java:159)
    at ai.core.server.gateway.GatewayMediaProvider.downloadVideo(GatewayMediaProvider.java:154)
    at ai.core.server.gateway.MediaJobContentController.videoContent(MediaJobContentController.java:43)
```

对应的请求：

```text
GET /api/media-jobs/361f2e56-7bc6-4375-b0be-6177ef3cf655/content
sec-fetch-dest=video
response_code=500
http_count=1
http_read_bytes=74   <-- 上游只返回了 74 字节的 JSON 错误
```

`http_read_bytes=74` 说明容器出网正常、请求确实到达了 Google，并拿到了一个结构化的 404；不是 Docker DNS、代理或证书问题。

### 1.3 数据库证据

`media_jobs` 中视频 job 的分布：

| provider | media_type | 数量 | 有 file_id |
|---|---|---:|---:|
| Gemini (`60099d91-...`) | video | 93 | 0 |
| KIE (`4641b97b-...`) | video | 20 | 0 |
| Volcano Ark (`f8e7218c-...`) | video | 3 | 0 |
| Gemini (`60099d91-...`) | 缺失（legacy 按视频展示） | 3 | 0 |

所有视频 job 的 `file_id`、`content_type`、`upstream_asset_url` 都是 `null`。最近几条：

```javascript
{
  _id: '361f2e56-7bc6-4375-b0be-6177ef3cf655',
  upstream_video_id: 'ChBkNGQyMDM4ZjZhNGI0ZTMxEAgaAjRmKgRtYWlu',
  requested_model: 'gemini-omni-1.1-flash',
  state: 'completed',
  file_id: null,
  content_type: null,
  created_at: ISODate('2026-10-25T14:03:21.917Z'),
  completed_at: ISODate('2026-10-25T14:04:19.036Z')
}
```

### 1.4 前端表现

`core-ai-frontend/src/pages/generations/Generations.tsx:341-345`：

```tsx
{preview.mediaType === 'image' && preview.fileId
  ? <img src={`/api/files/${preview.fileId}/content`} ... />
  : <video src={`/api/media-jobs/${preview.id}/content`} controls autoPlay
      className="max-h-[80vh] max-w-[90vw] rounded" onClick={e => e.stopPropagation()} />}
```

- `<video>` 拿到的是 **500 JSON**，浏览器触发 `MEDIA_ERR_SRC_NOT_SUPPORTED`；组件没有 `onError`，所以只显示一个黑色播放器。
- “Open in new tab” 会直接打开 500 JSON，用户看到的是 `{"errorCode":"INTERNAL_ERROR",...}`。
- 列表里的视频 tile 只是一个黑色方块 + Play 图标（`Generations.tsx:225-233`），没有海报、没有时长，也看不出“这个视频已经失效”。

---

## 2. 根因分析

### 2.1 主因：视频产物没有落库，预览时回源

视频提交链路（`GatewayMediaProvider.generateVideo`）：

```text
POST /api/gateway/v1/videos
  -> GatewayMediaProvider.generateVideo()
  -> upstream.generateVideo()
  -> MediaJobService.createVideoJob()
       job.upstreamVideoId = response.id()
       job.state = "submitted"
       // 没有下载、没有 fileId
```

预览链路：

```text
GET /api/media-jobs/:id/content
  -> MediaJobContentController.videoContent()
  -> mediaProvider.downloadVideo(GatewayVideoHandle.encode(job.id))
  -> GatewayMediaProvider.videoJob()  // 查 media_jobs
  -> GatewayMediaProvider.videoBytes(job)
  -> upstreamProvider.downloadVideo(job.upstreamVideoId)
```

`MediaJobContentController.java:42-45` 每次都把整段视频下载成 `byte[]`，再 `Response.bytes(...)` 一次性返回。**服务端没有任何“已经存过”的分支**。

### 2.2 上游资产是“借来的”，会过期

不同 provider 的 `downloadVideo` 语义：

| provider | `downloadVideo` 实际做什么 | 什么时候失效 |
|---|---|---|
| Vertex Gemini Omni | `GET /interactions/{upstreamVideoId}`，再从 `output.video.data`（base64）或 `uri` 取视频 | interaction 被上游清理后 GET 404；或 `uri` 本身是短时效地址 |
| KIE | 先 `GET /api/v1/jobs/recordInfo`，再下载 `resultUrls` | 任务记录/结果 URL 过期 |
| Volcano Ark | 先 `GET /tasks/{id}`，再下载 `content.video_url` | 任务记录/签名 URL 过期 |

Gemini Interactions API 的保留期（官方文档）：

- 免费层：**1 天**。
- 付费层：默认 **55 天**，可在 AI Studio 配置为 **7 / 14 / 28 / 55 天**。
- Vertex 预览版按 Google Cloud 的存储策略执行，文档没有承诺与 Gemini Developer API 相同的保留期。

参考：<https://ai.google.dev/gemini-api/docs/interactions-overview>、<https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/capabilities/interactions>。

这次故障的 job 完成于 10 月 25 日，查看于 11 月 3 日，间隔 9 天；如果项目配置的是 7 天保留期，或 Vertex 预览版有更短的 TTL，就正好解释 404。**即使 interaction 还在，`output.video.uri` 仍可能是单独签名的短时效地址**，所以“interaction 还在”也不等于“视频还能下载”。

另一个可能的原因是 provider 的 Vertex project / service account 被更换过：interaction 是 project 作用域的，换 project 后旧 ID 在新 project 下同样会 404。无论实际是哪一种，结论都一样——**平台不能把“上游还能取回”当作视频可播放的前提**。

### 2.3 次因：没有 Range / 流式，播放体验和资源占用都差

即使上游没过期，当前实现也有三个问题：

1. **全量缓冲**：`downloadVideo` 返回 `byte[]`，`Response.bytes` 再持有一份；一个 4K/10 秒视频可能几十 MB，并发预览会放大堆内存。
2. **不支持 Range**：`MediaJobContentController` 固定返回 200 全量，不解析 `Range`，不返回 `Accept-Ranges` / `Content-Range`。HTML5 `<video>` 的 seek、拖动进度条、部分浏览器起播都会退化为“先下载完整文件”。
3. **每次预览都回源**：没有缓存，用户每打开一次弹层就向上游再拉一次；provider 限流、出网成本、首帧延迟都被放大。

### 2.4 次因：错误处理缺失

- 服务端把 provider 的 404 包装成 `RuntimeException`，core-ng 统一转成 **500**。
- 前端 `<video>` 无法区分“还在归档”“上游已过期”“网络错误”，也没有 `onError` 兜底。
- 列表没有 `artifactState` 字段，前端无法在渲染播放器之前就知道“这个视频不可播放”。

### 2.5 次因：自动播放策略与海报

- `<video autoPlay>` 带音轨时会被 Chrome 的自动播放策略拦截；用户看到的是“打开了但没动”，容易误判为加载失败。
- 视频列表没有 poster，20 行视频全是黑块，无法快速定位内容。

### 2.6 为什么图片没有这个问题

图片走的是另一条路径（`MediaJobService.createImageJob`）：

```java
job.state = "completed";
...
storeImage(job, image);   // 下载/解码 -> FileService.uploadIfAbsent -> job.fileId
```

`storeImage` 在生成返回前就把字节写进了 `FileService`，所以图片预览永远读平台自己的存储；视频没有对应的 `storeVideo`。

---

## 3. 目标与非目标

### 3.1 目标

1. **完成即归档**：每个 `completed` 视频在完成后被物化到 `FileService`，`MediaJob.fileId` 可用；预览不再依赖上游资产。
2. **可播放、可 seek**：有对象存储时通过 307 让 Azure/MinIO 原生处理 HTTP Range；无对象存储时保证可播放，但明确不支持 seek。
3. **首屏快**：列表有海报和时长；弹层有 poster，点击播放立即起播；不重复回源。
4. **状态明确**：`pending / storing / stored / expired / failed` 五种状态在 API 和 UI 上都有明确表达；过期视频显示“原始视频已过期”，不再黑屏。
5. **热路径零重复下载**：agent 的 `get_video_status` 已经下载过的视频，直接关联 `file_id`，后台不再下载第二遍。
6. **可回填**：部署后能自动处理历史 job；能恢复的恢复，不能恢复的标记为过期。
7. **不破坏图片链路**：图片预览、圈选编辑、Compare models 行为不变。

### 3.2 非目标

- 不做 HLS / DASH 转码；10 秒左右的 MP4 渐进式播放足够。
- 不做视频编辑、剪辑、裁剪。
- 不做跨区域 CDN 加速（可作为后续优化）。
- 不改变 `MediaProvider` 的生成接口形状（只增加可选的“下载产物元数据”能力）。
- 不做对象存储的生命周期策略实现（只预留配置和清理 Job 的设计）。

---

## 4. 方案总览

### 4.1 架构

```text
                    ┌─────────────────────────────────────────────────────┐
                    │  生成阶段                                             │
                    │  GatewayMediaProvider.generateVideo()                │
                    │    -> createVideoJob(state=submitted)                │
                    └──────────────────────┬──────────────────────────────┘
                                           │
              ┌────────────────────────────┼────────────────────────────┐
              │ 热路径 A / B                │ 后台路径                    │ 懒加载路径
              ▼                            ▼                            ▼
  A. server 侧 get_video_status     MediaArtifactMaterializationJob    GET /api/media-jobs/:id/content
     ServerImageOutputSink          每 60s 扫描                       没有 fileId 时
     linker.link(handle, fileId)    - 轮询未终结 job                   requestMaterialization()
                                    - 物化 completed 且无 fileId        返回 409 + Retry-After
  B. Desktop/CLI 下载               - 生成 poster                      前端轮询后重试
     GET /api/gateway/v1/videos/:id/content
     下载即归档 storeArtifact
              │                            │                            │
              └────────────────────────────┴────────────────────────────┘
                                           ▼
                              media_jobs.file_id / artifact_state
                              media_jobs.poster_file_id
                                           │
                                           ▼
                              FileService (Azure Blob / MinIO / Mongo)
                                           │
                              GET /api/files/:id/content
                                 -> 307 预签名 URL（Range 由对象存储处理）
                              GET /api/gateway/v1/videos/:id/content
                                 -> 直接返回字节（Desktop/CLI 不跟随 307）
```

### 4.2 物化路径的优先级

| 路径 | 触发 | 优点 | 适用 |
|---|---|---|---|
| 热路径 | `get_video_status` 完成并保存；或 Desktop/CLI 请求 `/api/gateway/v1/videos/:id/content` | 零额外下载，延迟最低 | 绝大多数正常轮询的 job |
| 下载即归档 | 任何 content 端点成功下载了字节 | 把“用户本来就要下载”的流量变成归档 | Desktop/CLI、直接调 gateway 的客户端 |
| 后台路径 | 定时 Job | 覆盖所有 job，兜底 | 没人下载、agent 没保存、历史 job |
| 懒加载路径 | 用户打开预览 | 即时反馈 | 后台还没跑到的 job |

这些路径最终都调用同一个 `MediaArtifactService.storeArtifact(job, bytes, contentType)`，通过 `file_id` 幂等。

### 4.3 状态机

```text
(未完成)
  │ state -> completed
  ▼
pending ──claim──► storing ──成功──► stored
  ▲                   │
  │                   ├─ 404/410 ──► expired
  │                   └─ 其他错误 ──► failed
  │                                      │
  └────────── 重试（backoff）◄───────────┘
```

- `pending`：已完成，等待物化。
- `storing`：已被某个实例 claim，正在下载/上传；带 `artifact_claimed_at`，超时可回收。
- `stored`：`file_id` 已写入，预览可用。
- `expired`：上游明确 404/410，字节不可再取；不再重试。
- `failed`：网络/5xx/磁盘等可重试错误；按 1m、5m、15m、1h、6h 退避，最多 5 次。

---

## 5. 数据模型

### 5.1 `MediaJob` 新增字段

文件：`core-ai-server/src/main/java/ai/core/server/domain/MediaJob.java`

```java
@Field(name = "artifact_state")
public String artifactState;              // pending / storing / stored / expired / failed

@Field(name = "artifact_error")
public String artifactError;              // 给前端/运维看的原因，截断到 500 字符

@Field(name = "artifact_size")
public Long artifactSize;                 // bytes

@Field(name = "artifact_stored_at")
public ZonedDateTime artifactStoredAt;

@Field(name = "artifact_attempts")
public Integer artifactAttempts;          // 默认 0

@Field(name = "artifact_next_attempt_at")
public ZonedDateTime artifactNextAttemptAt;

@Field(name = "artifact_claimed_at")
public ZonedDateTime artifactClaimedAt;   // storing 租约，10 分钟未完成可回收

@Field(name = "poster_file_id")
public String posterFileId;               // 海报在 FileService 的记录 id

@Field(name = "duration_seconds")
public Double durationSeconds;            // 可选，来自 provider usage/ffprobe/JCodec
```

注意：Mongo entity 不允许 primitive，统一用包装类型；新增字段不需要给默认值，`null` 表示“旧数据/未开始”。

### 5.2 索引与迁移

新增迁移：`SchemaMigrationVMediaJobArtifact`，版本号建议 `20261103001`，在 `SchemaMigrationManager` 的迁移列表中注册。

```java
mongo.createIndex("media_jobs",
    Indexes.ascending("media_type", "state", "artifact_state", "completed_at"));
mongo.createIndex("media_jobs",
    Indexes.ascending("media_type", "state", "updated_at"));
mongo.createIndex("media_jobs",
    Indexes.ascending("artifact_state", "artifact_next_attempt_at"));
```

迁移本身只做两件事：

1. 给已有 `media_type=video && state=completed && file_id=null` 的 job 设置 `artifact_state=pending`。
2. 给已有 `file_id != null` 的 job 设置 `artifact_state=stored`（当前没有，但为未来兼容）。

`media_type` 缺失的 legacy 视频：查询条件用 `media_type in ["video", null]`，与 `MediaJobService.list` 的现有语义一致。

### 5.3 `MediaJobView` 新增字段

文件：`core-ai-api/src/main/java/ai/core/api/server/media/MediaJobView.java`

```java
@Property(name = "artifactState")
public String artifactState;

@Property(name = "artifactError")
public String artifactError;

@Property(name = "artifactSize")
public Long artifactSize;

@Property(name = "posterFileId")
public String posterFileId;

@Property(name = "durationSeconds")
public Double durationSeconds;

/** 前端直接可用的播放地址；stored 时指向 /api/files/{fileId}/content，否则指向 /api/media-jobs/{id}/content */
@Property(name = "contentUrl")
public String contentUrl;

/** 是否已经有平台存储的字节；等价于 fileId != null */
@Property(name = "playable")
public Boolean playable;
```

`MediaJobWebServiceImpl.toView()` 里补齐：

```java
view.artifactState = job.artifactState;
view.artifactError = job.artifactError;
view.artifactSize = job.artifactSize;
view.posterFileId = job.posterFileId;
view.durationSeconds = job.durationSeconds;
view.playable = job.fileId != null;
view.contentUrl = job.fileId != null
        ? "/api/files/" + job.fileId + "/content"
        : "/api/media-jobs/" + job.id + "/content";
```

兼容旧数据：如果 `artifactState` 为 null 且 `state=completed`，view 按 `pending` 返回，避免前端看到 `undefined` 而误判。也可以在 `MediaJobWebServiceImpl` 里统一做一个 `effectiveArtifactState(job)`。

这样前端不再自己拼 URL，也不会在 `fileId` 已经存在时还走回源端点。

---

## 6. 服务端改造

### 6.1 `MediaArtifactService`

新类，放在 `ai.core.server.gateway`（与 `MediaJobService` 同包，便于访问 `MediaJob` 和 provider）。

职责：

```java
public class MediaArtifactService {
    // 幂等：已经是 stored 直接返回；正在 storing 返回当前状态
    public MediaJob storeArtifact(MediaJob job, byte[] bytes, String contentType);

    // 后台/懒加载用：从 provider 下载 -> 校验大小 -> storeArtifact -> 可选生成 poster
    public MediaJob materialize(MediaJob job);

    // 尝试从 provider 下载；404/410 转成 MediaArtifactUnavailableException
    public byte[] download(MediaJob job);

    // 生成海报（JCodec 或 ffmpeg），失败不阻塞主流程
    public void generatePoster(MediaJob job);
}
```

`storeArtifact` 的核心逻辑：

```java
if (job.fileId != null) {
    markStored(job, job.fileId, job.contentType, job.artifactSize);
    return job;
}
if (bytes == null || bytes.length == 0) {
    throw new MediaArtifactUnavailableException("empty video body");
}
if (bytes.length > MAX_VIDEO_BYTES) {
    throw new IllegalStateException("video too large: " + bytes.length);
}
var record = fileService.uploadIfAbsent(
        job.userId,
        "generated-video-" + job.id + FileService.extension(type),
        type,
        tempFile);
markStored(job, record.id, record.contentType, record.size());
```

- `MAX_VIDEO_BYTES` 建议配置化，默认 512 MB；provider 10 秒视频远低于此。
- `uploadIfAbsent` 按 `user_id + md5(content)` 去重，热路径和后台路径重复保存同一个视频时会复用同一个 `FileRecord`。
- 失败时不要把 job 标记成 `failed` 除非确认是视频本身不可用；网络错误保留 `pending`，交给后台重试。

`markStored` 用一次 Mongo update 完成：

```java
mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
    Updates.set("file_id", record.id),
    Updates.set("file_name", record.fileName),
    Updates.set("content_type", record.contentType),
    Updates.set("artifact_state", "stored"),
    Updates.set("artifact_error", null),
    Updates.set("artifact_size", record.size),
    Updates.set("artifact_stored_at", now),
    Updates.unset("artifact_claimed_at"),
    Updates.set("updated_at", now)));
```

> 可选优化：Vertex 的 completion `GET /interactions/{id}` 响应本身已经带 `output.video`（base64 或 uri），可以在 `getVideoStatus` 返回一个可选的 artifact，省掉 `downloadVideo` 的第二次 GET。为了不扩大 `MediaProvider` SPI 的改动面，第一版仍按“完成后单独 `downloadVideo` 一次”实现；等物化稳定后再评估。

### 6.2 后台物化 Job

新类：`MediaArtifactMaterializationJob implements Job`，在 `GatewayModule` 里注册：

```java
schedule().fixedRate("media-artifact-materialization",
        bind(MediaArtifactMaterializationJob.class), Duration.ofMinutes(1));
```

每个 tick 做三件事，全部有 batch 上限，避免 provider 限流：

#### 第一步：回收超时租约

```java
mediaJobCollection.update(
    Filters.and(
        Filters.eq("artifact_state", "storing"),
        Filters.lt("artifact_claimed_at", now.minusMinutes(10))),
    Updates.combine(
        Updates.set("artifact_state", "pending"),
        Updates.unset("artifact_claimed_at")));
```

#### 第二步：轮询未终结的视频 job

```java
filter = and(
    media_type in ["video", null],
    state in ["submitted", "queued", "processing"],
    updated_at < now - 2 minutes,
    created_at > now - 7 days);   // 太老的 job 上游多半已经没了，避免无意义轮询
sort = ascending("created_at");
limit = 20;
```

对每条调用 `provider.getVideoStatus(job.upstreamVideoId)`，走 `MediaJobService.updateVideoStatus`。失败只记日志，不把 job 标记 failed（网络抖动不应误伤）。

可配置项（建议都放到 system settings 或 `MediaProperties`）：

```text
media.artifact.materialize.batch-size = 3       # 每 tick 物化条数
media.artifact.poll.batch-size = 20             # 每 tick 轮询条数
media.artifact.max-bytes = 536870912            # 512 MB 上限
media.artifact.retry.max = 5                    # 最大重试次数
media.artifact.retention-days = 90              # 可选，P3
```

#### 第三步：物化已完成但没有 fileId 的视频

```java
filter = and(
    media_type in ["video", null],
    state == "completed",
    file_id == null,
    or(
        artifact_state == null,
        artifact_state in ["pending", "failed"]),
    or(artifact_next_attempt_at == null, artifact_next_attempt_at <= now));
sort = ascending("completed_at");   // 先救最老的，趁上游还没过期
limit = 3;                          // 每 tick 最多 3 个，控制内存和上游压力
```

对每条：

1. **先尝试关联已有文件**（见 6.3）：`findVideoArtifactByHandle(job)`。
   - 命中：直接 `markStored`，不下载。
2. 否则 `claimForMaterialization(job)`：

```java
var claimed = mediaJobCollection.update(
    Filters.and(
        Filters.eq("_id", job.id),
        Filters.eq("state", "completed"),
        Filters.eq("file_id", null),
        Filters.or(
            Filters.exists("artifact_state", false),
            Filters.in("artifact_state", List.of("pending", "failed")))),
    Updates.combine(
        Updates.set("artifact_state", "storing"),
        Updates.set("artifact_claimed_at", now),
        Updates.inc("artifact_attempts", 1)));
if (claimed == 0) return;   // 另一个实例已经抢到
```

3. `download(job)`：
   - 成功：`storeArtifact` → `stored`；然后 `generatePoster`（异步/同 tick 都可，海报失败不影响 stored）。
   - `MediaArtifactUnavailableException`（404/410/明确 not found）：`expired`，`artifact_error` 记录上游消息；不再重试。
   - 其他异常：`failed`，`artifact_next_attempt_at = now + backoff(attempts)`；`attempts >= 5` 时直接 `expired` 或 `failed` 终态，避免无限重试。
4. 每处理一条都 `ActionLogContext.put("media_job_id", job.id)`，日志输出 `state / bytes / elapsed / provider`。

#### 多副本安全

- claim 是 Mongo 条件更新，天然只有一个实例能抢到同一条 job。
- 租约 10 分钟回收，覆盖 pod 崩溃在 `storing` 的情况。
- 轮询未终结 job 不做 claim，多个副本可能重复轮询；`getVideoStatus` 本身有 5 秒缓存（`GatewayMediaProvider.VIDEO_STATUS_CACHE_TTL`），影响可接受。若要严格，可给轮询也加一个 `status_claimed_at` 租约。

### 6.3 热路径：`get_video_status` 保存后直接关联

现状：

```text
GetVideoStatusTool.execute()
  -> provider.downloadVideo(videoId)
  -> saveVideo(context, bytes, videoId)
       -> VideoOutputSink.save("video-" + videoId + ".mp4", "video/mp4", bytes)
            -> ServerImageOutputSink.save()
                 -> fileService.uploadIfAbsent(...)
                 -> 返回共享 URL
```

这条路径已经把视频存进了 `FileService`，但 `MediaJob` 不知道。改造点：

1. 新增一个窄接口（放在 `ai.core.server.artifact` 或 `ai.core.server.media`，避免包循环）：

```java
public interface MediaArtifactLinker {
    /** 把已保存的视频文件关联回 media job；mediaId 是 gateway handle（gateway-media-v1.vid.xxx） */
    void link(String mediaId, String fileId, String fileName, String contentType, Long size);
}
```

2. `ServerImageOutputSink` 增加可选构造参数 `MediaArtifactLinker linker`；在 `save()` 成功后：

```java
if (fileName.startsWith("video-") && fileName.endsWith(".mp4")) {
    var mediaId = fileName.substring("video-".length(), fileName.length() - ".mp4".length());
    if (GatewayMediaHandle.isHandle(mediaId)) {
        linker.link(mediaId, record.id, record.fileName, record.contentType, record.size);
    }
}
```

3. `MediaJobService implements MediaArtifactLinker`：

```java
public void link(String mediaId, String fileId, String fileName, String contentType, Long size) {
    var handle = GatewayMediaHandle.decode(mediaId);
    mediaJobCollection.update(
        Filters.and(
            Filters.eq("_id", handle.jobId()),
            Filters.eq("media_type", "video")),
        Updates.combine(
            Updates.set("file_id", fileId),
            Updates.set("file_name", fileName),
            Updates.set("content_type", contentType),
            Updates.set("artifact_state", "stored"),
            Updates.set("artifact_size", size),
            Updates.set("artifact_stored_at", ZonedDateTime.now()),
            Updates.unset("artifact_error"),
            Updates.unset("artifact_claimed_at")));
}
```

4. 两个构造 `ServerImageOutputSink` 的地方传入 linker：
   - `AgentRunBuilder.buildExecutionContext()`
   - `SessionContextBuilder.build()`

这样 agent 轮询完成的视频在生成页立即可播，后台 Job 也不会再下载一遍。

> 适用范围：热路径覆盖 **server 侧 agent 会话**（`AgentRunBuilder` / `SessionContextBuilder` 构造的 context）和 **Desktop/CLI 通过 gateway 下载视频**（`LiteLLMMediaProvider.downloadVideo` → `GET /api/gateway/v1/videos/:id/content`）。如果客户端完全绕过 gateway、直接在 provider 侧保存，server 看不到字节，由后台 Job 和懒加载路径兜底。这也是为什么后台物化不能省。

### 6.4 内容端点改造

文件：`core-ai-server/src/main/java/ai/core/server/gateway/MediaJobContentController.java`

目标：图片行为不变；视频按存储状态分流。

```java
public Response content(Request request) {
    var job = mediaJobService.get(request.pathParam("id"));
    if ("image".equals(job.mediaType)) return imageContent(job);
    return videoContent(job, request);
}

private Response videoContent(MediaJob job, Request request) {
    if (job.fileId != null) {
        var record = fileService.get(job.fileId);
        return fileContent(record);       // 307 到预签名 URL，或 Mongo fallback + Range
    }
    if (!"completed".equals(job.state)) {
        return notReady(job, "video is still " + job.state);
    }
    // 懒加载：触发一次物化，但不阻塞播放器请求
    mediaArtifactService.requestMaterialization(job);
    return notReady(job, "video artifact is being archived");
}
```

#### 6.4.1 `GatewayVideoController.content`：下载即归档，且必须直接返回字节

Desktop/CLI 的 `get_video_status` 走的是 `/api/gateway/v1/videos/:id/content`，它本来就要等一次完整下载。这里不要返回 307：`LiteLLMMediaProvider` 的 `downloadClient` 由 `PatchedHTTPClientBuilder` 构建，明确设置了 `followRedirects(false)`（`PatchedHTTPClientBuilder.java:107`），旧的 Desktop/CLI 客户端不会跟随重定向，收到 307 只会拿到空 body 并报错。

正确做法是“下载即归档 + 直接返回字节”：

```java
Response content(String videoId, MediaJobOwner owner) {
    var job = gatewayMediaProvider.videoJob(videoId, owner);
    var record = mediaArtifactService.getOrMaterialize(job);  // 同步：已存直接取，未存下载并存储
    return Response.bytes(mediaArtifactService.bytes(record))
            .contentType(ContentType.parse(record.contentType));
}
```

- 成功下载的字节在返回前就写入 `FileService`，下一次生成页预览直接命中。
- 首次下载可以直接返回刚下载的 `bytes`，避免“上传 Blob 后立刻再从 Blob 读回”的双倍延迟；后续请求再从 `FileService` 读。
- 这里不能用 307，否则会破坏 Desktop/CLI 的下载；浏览器路径（`MediaJobContentController`）才用 307。
- 10 秒视频的 `byte[]` 内存可接受；如果未来支持长视频，改用 `Response.file(tempFile)` 或给 `LiteLLMMediaProvider` 增加手动跟随重定向，二者都需要单独评估。
- 下载失败（404/410）抛 `MediaArtifactUnavailableException`，由 controller 转 410，不要让 tool 拿到 500 堆栈。

#### 已存储：307 + 对象存储 Range

复用 `FileResponseSupport.content(record, fileService)` 的逻辑。注意它当前是 `file` 包内的 package-private 类（`FileResponseSupport.java:15`），`MediaJobContentController` 在 `gateway` 包，不能直接调用；实现时把 `FileResponseSupport` 提升为 `public`，或抽出 `FileContentResponse` 公共 helper：

```java
var downloadUrl = fileService.downloadUrl(record);
if (downloadUrl != null) {
    return Response.redirect(downloadUrl, HTTPStatus.TEMPORARY_REDIRECT)
            .header(HTTPHeaders.CACHE_CONTROL, "no-store");
}
// 无对象存储：Mongo base64，全量 200（core-ng 无 206，见下文限制）
return fullResponse(record);
```

- Azure Blob / MinIO 的预签名 GET 原生支持 `Range`、`ETag`、`Content-Length`，浏览器 `<video>` 的 seek 直接由存储处理。
- 307 必须 `no-store`，避免缓存到已过期的 SAS URL（与 `FileResponseSupport` 的现有注释一致）。
- 不要把预签名 URL 写进 `MediaJobView.contentUrl`；前端始终用平台 URL，由服务端每次签新 URL。
- 如果 `job.fileId` 存在但 `FileRecord` 已经被删除（存储清理/迁移异常），按“未存储”处理：把 `file_id` 置空、`artifact_state=pending`，交给后台重新物化或标记过期。

#### 未存储：409 + Retry-After

```java
return Response.bytes(JsonUtil.toJson(Map.of(
        "errorCode", "MEDIA_ARTIFACT_NOT_READY",
        "message", message,
        "artifactState", job.artifactState))
    .getBytes(StandardCharsets.UTF_8))
    .status(HTTPStatus.CONFLICT)
    .contentType(ContentType.APPLICATION_JSON)
    .header("Retry-After", "2");
```

前端拿到 409 后轮询 `GET /api/media-jobs?offset=...` 或新增的 `GET /api/media-jobs/:id`，直到 `playable=true` 或 `artifactState=expired`。

`requestMaterialization` 不要只等每分钟一次的定时 Job：用一个有界线程池（core=2, max=4, queue=100）立即执行 `materialize(job)`，重复请求由 Mongo claim 去重；线程池满时直接返回 409，让定时 Job 兜底。这样用户点开预览后通常 2–5 秒内就能播放。

#### 已过期：410 + 明确错误码

```java
return Response.bytes(JsonUtil.toJson(Map.of(
        "errorCode", "MEDIA_ARTIFACT_EXPIRED",
        "message", "原始视频已过期，上游只保留有限时间；该任务完成时未归档。",
        "artifactState", "expired"))
    .getBytes(StandardCharsets.UTF_8))
    .status(HTTPStatus.GONE)
    .contentType(ContentType.APPLICATION_JSON);
```

`HTTPStatus.GONE` 在 core-ng `HTTPStatus` 中存在（已确认）。不要抛 `RuntimeException`，避免 500 和堆栈噪音。

#### 无对象存储时的 fallback（重要限制）

如果 `fileService.downloadUrl(record)` 为 null（Mongo base64），只能返回 **200 全量**：

```java
var data = fileService.getBytes(record);
return Response.bytes(data)
    .contentType(...)
    .header(HTTPHeaders.CACHE_CONTROL, "private, max-age=3600")
    .header("Accept-Ranges", "none")
    .header("ETag", etag(record));
```

**为什么不在应用内实现 206**：core-ng 的 `core.framework.api.http.HTTPStatus` 是 final enum，当前版本只定义了 200/201/204/3xx/4xx/5xx，**没有 206 Partial Content，也没有 416 Range Not Satisfiable**；`Response.status()` 只接受这个 enum，`Response.file()` 的 `FileBody` 也不解析 `Range`。因此在应用层强行返回 206 需要：

1. 扩展/替换 core-ng 的 `HTTPStatus` 与 `Response`（改框架，成本高）；或
2. 在 core-ng 之外用 Undertow 原生 handler 直接写响应（绕过统一响应管道，风险高）；或
3. 在前面加一层支持 Range 的反向代理/CDN（推荐给无对象存储的自托管部署）。

本方案的取舍是：**把对象存储作为视频预览的推荐前提**。生产/当前 Docker 已经配置 Azure Blob，307 路径天然支持 Range；没有对象存储的开发环境仍能播放 10 秒左右的 MP4，只是拖动进度条会退化为重新请求全量。这个限制会写进部署文档，而不是假装应用层支持了 206。

> 如果未来必须支持“无对象存储 + seek”，优先给 core-ng 提 PR 增加 `PARTIAL_CONTENT` / `REQUESTED_RANGE_NOT_SATISFIABLE`，或在 core-ng 前挂 nginx；不要在当前代码里用反射/自定义 Response 绕过框架。

### 6.5 Provider 错误分类

新增受检/运行时异常，放在 `ai.core.media` 或 `ai.core.server.gateway`：

```java
public class MediaArtifactUnavailableException extends RuntimeException {
    public MediaArtifactUnavailableException(String message) { super(message); }
}
```

实现方式有两种，建议先用“包装”而不是改所有 provider：

```java
// GatewayMediaProvider.videoBytes()
try {
    return upstreamProvider(...).downloadVideo(job.upstreamVideoId);
} catch (RuntimeException e) {
    if (isNotFound(e.getMessage())) {
        throw new MediaArtifactUnavailableException(
            "upstream video is no longer available: " + e.getMessage());
    }
    throw e;
}

private boolean isNotFound(String message) {
    if (message == null) return false;
    var lower = message.toLowerCase(Locale.ROOT);
    return lower.contains("http 404")
        || lower.contains("http 410")
        || lower.contains("not found")
        || lower.contains("not_found")
        || lower.contains("requested entity was not found");
}
```

更干净的长期方案是在各 provider 的 `execute()` 里对 404/410 抛 `MediaArtifactUnavailableException`，但会触碰 `VertexGeminiOmniMediaProvider`、`KieMediaProvider`、`ArkMediaProvider` 和测试，建议作为 P1 的重构项，不阻塞主方案。

### 6.6 海报生成

海报的目标是列表 tile 和弹层 poster，不要求高分辨率。

**推荐：JCodec（纯 Java，不依赖 ffmpeg）**

- 依赖：`org.jcodec:jcodec:0.2.5`、`org.jcodec:jcodec-javase:0.2.5`。
- 从存好的 MP4 取第 1 秒（避免首帧黑屏）：

```java
var frame = FrameGrab.getFrameAtSec(tempFile.toFile(), 1.0);
var image = AWTUtil.toBufferedImage(frame);
var poster = ImageJpegCodec.downscale(toJpeg(image), 640, 0.8f);
var record = fileService.uploadIfAbsent(
        job.userId, "poster-" + job.id + ".jpg", "image/jpeg", tempFile);
```

- 失败兜底：不生成 poster，列表继续用现有的黑色 Play tile；不要因为海报失败把 job 标成 `failed`。
- 如果部署镜像已经有 ffmpeg，可以用 `ffmpeg -ss 1 -i video.mp4 -frames:v 1 -vf scale=640:-1 poster.jpg` 作为更稳的备选；但不要把 ffmpeg 变成 API 进程的硬依赖（现有 `LocalFfmpegRunner` 的注释也明确说 ffmpeg 是 CPU 重、时长不可控，应该放在 sandbox 里）。

**存储**

- `poster_file_id` 指向 `FileService` 的 JPEG 记录。
- 列表用 `/api/files/{posterFileId}/thumbnail`（FileService 会生成 256px 缩略图）。
- 弹层 poster 用 `/api/files/{posterFileId}/content`。

### 6.7 缓存与存储生命周期

- 视频对象：上传时设置 `Cache-Control: private, max-age=86400`（或按部署策略），让浏览器在一天内重复打开不必重新下载。
- 307 重定向：`Cache-Control: no-store`，每次签新 SAS。
- 存储保留：新增配置 `media.video.retention-days`（默认 90 天）和一个清理 Job：
  - 删除过期的 `FileRecord` 和 blob；
  - 把 `media_jobs.artifact_state` 置为 `expired`，保留 job 行用于成本/审计；
  - 如果视频被 `AgentRunArtifact` 或项目引用，跳过删除（先查引用再删）。
- 成本：10 秒 720p 约 3–10 MB；1000 条约 5–10 GB。Azure Hot 存储成本很低，主要成本是出网；预览直接从 Blob 读取，不经过 API 进程，后续可接 CDN。

---

## 7. 前端改造

### 7.1 类型

`core-ai-frontend/src/api/client.ts` 的 `MediaJob` 增加：

```ts
artifactState?: 'pending' | 'storing' | 'stored' | 'expired' | 'failed';
artifactError?: string;
artifactSize?: number;
posterFileId?: string;
durationSeconds?: number;
contentUrl?: string;
playable?: boolean;
```

### 7.2 列表 tile

`Generations.tsx` 的 Output 列，视频分支改成：

```tsx
: job.mediaType === 'video' && job.playable
  ? <div onClick={() => setPreview(job)} className="relative h-16 w-[88px] ...">
      {job.posterFileId
        ? <img src={`/api/files/${job.posterFileId}/thumbnail`} className="h-full w-full object-cover" />
        : <div className="h-full w-full bg-slate-900" />}
      <Play className="absolute inset-0 m-auto" size={20} fill="#fff" color="#fff" />
      {job.durationSeconds != null || job.requestedSeconds != null
        ? <span className="absolute bottom-1 right-1 ...">{formatDuration(job.durationSeconds ?? job.requestedSeconds!)}</span>
        : null}
    </div>
  : job.state === 'completed'
    ? <div title="视频已过期或尚未归档" className="...">
        <Film size={14} />
        <span className="text-[10px]">{job.artifactState === 'expired' ? '已过期' : '归档中'}</span>
      </div>
    : <div><Film size={14} /></div>
```

- `posterFileId` 存在时优先海报；没有海报时退回黑块 + Play。
- `artifactState === 'expired'` 时显示“已过期”，不要给用户“点了能播”的错觉。
- `state=processing` 时显示进度（`job.progress`），与现有状态列一致。

### 7.3 预览弹层

> 实际实现：弹层默认显示 `poster` 封面 + 播放按钮，点击后才挂载 `<video autoPlay>`，不再自动播放；没有封面时显示深色占位和 Play 按钮。

当前弹层是垂直堆叠：输入图、播放器、prompt、模型/时间、recipe。建议改成两栏（窄屏堆叠）：

```text
┌──────────────────────────────────────────────────────────────┐
│  [视频舞台 / poster / 状态覆盖层]        │  Prompt              │
│                                          │  Inputs              │
│                                          │  Model / Time        │
│                                          │  Recipe / Cost       │
│                                          │  [下载] [新标签打开]  │
└──────────────────────────────────────────────────────────────┘
```

- 视频舞台：黑色背景，`aspect-video` 或按 `requestedSize` 推导比例；最大高度 `80vh`。
- 覆盖层状态：
  - `loading`：spinner + “加载视频…”。
  - `archiving`：spinner + “视频正在归档，通常几秒内完成” + 轮询。
  - `expired`：Film 图标 + “原始视频已过期” + 说明 + “用相同 prompt 重新生成”（可选，需要视频重跑入口）。
  - `error`：警告图标 + “加载失败” + “重试” + “打开原视频”。
- 底部操作：下载（`/api/files/{fileId}/content?download=1`）、在新标签打开、复制 prompt。
- 键盘：Esc 关闭；Space 播放/暂停；←/→ 快退/快进 5s；M 静音。

### 7.4 `VideoPlayer` 组件

新建 `core-ai-frontend/src/components/VideoPlayer.tsx`：

```tsx
type VideoPlayerProps = {
  job: MediaJob;
  variant: 'modal' | 'tile' | 'inline';
  autoPlay?: boolean;
  onRequestRefresh?: () => void;
};

export function VideoPlayer({ job, variant, autoPlay, onRequestRefresh }: VideoPlayerProps) {
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [muted, setMuted] = useState(autoPlay === true); // 自动播放先静音
  const videoRef = useRef<HTMLVideoElement>(null);

  if (!job.playable) {
    if (job.state === 'completed' && (job.artifactState === 'pending' || job.artifactState === 'storing' || !job.artifactState)) {
      return <ArchivingState onRetry={onRequestRefresh} />;
    }
    if (job.artifactState === 'expired') return <ExpiredState error={job.artifactError} />;
    if (job.artifactState === 'failed') return <ArchiveFailedState error={job.artifactError} />;
    if (job.state === 'failed') return <FailedState error={job.error} />;
    return <ProcessingState progress={job.progress} />;
  }

  return (
    <div className="relative">
      <video
        ref={videoRef}
        src={job.contentUrl ?? `/api/media-jobs/${job.id}/content`}
        poster={job.posterFileId ? `/api/files/${job.posterFileId}/content` : undefined}
        controls={variant === 'modal'}
        muted={muted}
        playsInline
        preload={variant === 'tile' ? 'metadata' : 'auto'}
        autoPlay={autoPlay}
        onLoadedData={() => setState('ready')}
        onError={() => setState('error')}
        className="..."
      />
      {state === 'loading' && <Spinner overlay />}
      {state === 'error' && <ErrorOverlay onRetry={() => { videoRef.current?.load(); }} />}
      {autoPlay && muted && <button onClick={() => setMuted(false)}>开启声音</button>}
    </div>
  );
}
```

要点：

- **不要**在 `onError` 里直接把 `src` 清空；先展示错误覆盖层，让用户重试。
- `src` 变化时（物化完成后 `playable` 从 false 变 true），组件重新挂载 `<video>`；用 `key={job.fileId ?? job.id}` 强制刷新。
- 自动播放：默认 `muted=true` 才能自动播放；显示“开启声音”按钮。或者不自动播放，显示 poster + 大 Play 按钮，点击后带声音播放。两者都行，推荐后者用于弹层，前者用于 hover 预览。
- `tile` 变体不渲染 `controls`，只用于列表 hover 预览；`inline` 用于输入视频缩略图。

### 7.5 轮询

- 列表：如果当前页存在 `state` 为 `submitted/queued/processing` 的视频，每 5 秒刷新一次列表；全部终结后停止。
- 弹层：如果 `job.state=completed` 但 `playable=false`，每 2 秒刷新该 job（建议新增 `GET /api/media-jobs/:id`；没有该端点时临时用 `list` 接口按 offset 扫描，不推荐），最多 30 秒；超过后显示“归档超时，请稍后刷新”。
- 归档完成后，`artifactState=stored`、`playable=true`、`contentUrl` 变为 `/api/files/...`，`VideoPlayer` 自动切到可播放状态。

### 7.6 输入视频

弹层顶部的输入视频（`Generations.tsx:326-327`）目前也是直接 `<video src={inputContentUrl(input)}>`。输入项不是完整的 `MediaJob`，没有 `playable/artifactState`；用一个轻量的 `InlineVideo`，只处理加载失败：

```tsx
<InlineVideo
  src={inputContentUrl(input)}
  poster={input.fileId && input.modality !== 'video' ? `/api/files/${input.fileId}/thumbnail` : undefined}
  muted
/>
```

`InlineVideo` 在 `onError` 时显示一个视频占位图，不阻塞弹层。如果输入视频来自一个已完成但未物化的 job，内容端点的懒加载会尝试归档；失败就显示占位图，不影响主视频的预览。`inputThumbnailUrl` 已经对视频返回 null，保持不变。

### 7.7 列表 hover 预览（可选，P2）

如果产品希望“鼠标停在 tile 上就动起来”，在 §7.2 的海报 tile 上加：

- `onMouseEnter` 延迟 300ms 再挂载 `<video muted loop playsInline preload="metadata" src={job.contentUrl} poster={...}>`，`onMouseLeave` 立即暂停并卸载。
- 用父组件的 `hoveredJobId` 保证同一时间只有一个 `<video>` 在播放；20 行不会同时拉 20 个视频。
- 只有 `job.playable` 时才启用 hover；`expired/archiving` 不挂载视频。
- 用 `IntersectionObserver` 确保离屏行不会因为 hover 状态残留而继续下载。
- hover 预览不显示 controls，不播放声音；点击 tile 才打开完整弹层。

这个功能依赖 `contentUrl` 稳定和 307/Range 可用；如果对象存储未配置，hover 预览会退化为“下载完整文件”，建议只在有对象存储的部署启用。

---

## 8. 兼容与回填

### 8.1 现有 119 条视频 job

- 部署迁移后，所有 `completed && file_id=null` 的视频变成 `pending`。
- 后台 Job 按 `completed_at` 升序处理，每 tick 3 条：
  - 10 月 25 日的 Gemini job：上游 404 → `expired`，前端显示“已过期”。
  - 9 月的 KIE/Ark job：大概率也已过期 → `expired`。
  - 如果某些 provider 的结果 URL 还在有效期内：下载成功 → `stored`，可立即播放。
- 预计 1–2 小时内处理完 119 条（取决于 provider 响应和限流）。

### 8.2 先关联已有文件，再下载

`get_video_status` 历史上保存过一些视频，文件名是：

```text
video-gateway-media-v1.vid.<base64url(jobId)>.mp4
```

后台 Job 在下载前先查：

```java
var handle = GatewayMediaHandle.encodeVideo(job.id);
var fileName = "video-" + handle + ".mp4";
var existing = fileRecordCollection.findOne(Filters.and(
        Filters.eq("user_id", job.userId),
        Filters.eq("file_name", fileName)));
if (existing != null) {
    markStored(job, existing.id, existing.contentType, existing.size);
    return;
}
```

这能恢复那些“agent 已经保存、但 MediaJob 没关联”的视频，避免二次下载。当前库里有 4 条 `video-gateway-media-v1.*` 记录（9 月 1 日），虽然不在本次 10 月 25 日 job 范围内，但未来会命中。

### 8.3 无法恢复的历史视频

对 404/410 的 job：

- `artifact_state=expired`
- `artifact_error` 存上游原始错误（截断）
- `file_id` 保持 null
- 前端显示明确提示，不再请求 `/content`，避免每次打开都打上游 404

这是有意的产品行为：**承认资产已经丢失，而不是让用户面对一个黑屏播放器**。

> 一个可能的线下恢复途径：如果生成时 Desktop/CLI 执行过 `get_video_status`，它会把视频保存到本地 workspace 的 `.core-ai/media/videos/video-<handle>.mp4`（`LocalArtifactSink`）。服务端没有这份字节，但用户本机可能有；可以手动找回并在聊天里重新提交为 artifact。这个路径不能替代服务端归档，只是历史数据的应急手段。

---

## 9. 性能、成本、安全

### 9.1 性能

| 场景 | 现状 | 方案后 |
|---|---|---|
| 首次预览 | 回源下载全量，几百 ms 到几十秒 | 307 到对象存储，Range 起播，通常 < 1s |
| 再次预览 | 再回源一次 | 浏览器/对象存储缓存，API 只做一次 307 |
| seek | 不支持，重新下载 | 对象存储原生 Range，立即响应 |
| 内存 | `byte[]` 全量 + `Response.bytes` 副本 | 浏览器预览走 307，API 不持有；Desktop/CLI 下载仍走 byte[]，但每个 job 只下载一次 |
| 并发 | 每个预览一个上游请求 | 每个预览一个 307，无上游请求 |

### 9.2 成本

- 上游下载从“每次预览”变成“每个 job 一次”，显著降低 provider 出网和限流风险。
- 平台存储增加：按 1000 条/月、每条 5 MB 估算，约 5 GB/月；设置 90 天保留后总量可控。
- 预览出网从 API 进程转移到对象存储；如果出网费用敏感，后续可在 Blob 前加 CDN。

### 9.3 安全

- `/api/media-jobs/:id/content` 当前是 `@PermissionsBypass`，只靠 UUID 不可猜；改造后仍然如此。建议后续增加：
  - 已登录用户校验 `job.userId == currentUser` 或具备 `trace.view`；
  - 或由服务端签发短时效 `?token=`，避免 URL 被日志/分享泄露后长期可读。
- 307 到预签名 URL 的地址是短期 bearer token；`no-store` 防止缓存，避免过期后 403。
- 存储路径按 `FileRecord.id` 生成，不使用用户输入的文件名，避免路径穿越。

---

## 10. 可观测性

日志字段：

```text
action=media:artifact:materialize
media_job_id=...
provider=gemini|kie|volcano-ark
artifact_state=stored|expired|failed
bytes=...
elapsed_ms=...
attempt=...
```

指标（如果已有 metrics 管道）：

- `media_artifact_materialize_total{state=stored|expired|failed}`
- `media_artifact_materialize_duration_ms`
- `media_artifact_pending_jobs`（Gauge，后台 Job 每次 tick 后上报）
- `media_artifact_expired_total`

告警建议：

- `pending` 超过 30 分钟仍未 `stored` 的 job 数 > 10；
- `failed` 速率突然上升（provider 故障/凭证过期）；
- `expired` 速率上升（说明上游保留期变短或物化没跑）。

---

## 11. 测试计划

### 11.1 单元测试

`MediaJobServiceTest`：

- `createVideoJob` 后 `artifactState` 为 null/`pending`（按实现选择）。
- `updateVideoStatus` 从 `processing` → `completed` 时设置 `artifact_state=pending`，且只设置一次。
- `link()` 把 fileId 写入正确的 job，不覆盖其他字段。
- `markStored` 幂等：重复调用不改变 `artifact_stored_at`（或按设计更新）。

`MediaArtifactMaterializationJobTest`：

- 已 `stored` 的 job 不会被再次 claim。
- 两个并发 claim 只有一个成功。
- 404 → `expired`，不重试。
- 500 → `failed`，`next_attempt_at` 递增。
- `attempts >= 5` → 终态。
- 租约超时 → 重置为 `pending`。

`MediaJobContentControllerTest`：

- `fileId != null` → 307，`Location` 是预签名 URL，`Cache-Control: no-store`。
- `fileId == null && completed` → 409 + `Retry-After`，且触发一次 `requestMaterialization`。
- `expired` → 410 + `errorCode=MEDIA_ARTIFACT_EXPIRED`。
- 无对象存储 + `Range: bytes=0-99` → 200 全量（core-ng 无 206），只能播放、不能 seek；如需 seek，部署时配置对象存储或前置 nginx。
- 图片路径不变。

### 11.2 集成测试

- 用 mock `MediaProvider` 返回固定 MP4 字节：
  1. 创建 job → completed；
  2. `GET /content` → 409；
  3. 后台 Job 跑一次 → `fileId` 有值，`artifactState=stored`；
  4. 再 `GET /content` → 307/200；
  5. 第二次预览不再调用 provider。
- provider 抛 `HTTP 404` → job 变 `expired`，`GET /content` 410。
- `get_video_status` 热路径：mock sink 保存后调用 linker，job 立即 `stored`，后台 Job 不再下载。

### 11.3 手动/E2E

1. 在 Docker 环境生成一个 5–10 秒视频。
2. 等 agent 轮询完成；打开 `/generations`，确认：
   - 列表有海报/时长（如果 poster 已生成）；
   - 点击 tile，弹层直接播放；
   - 拖动进度条，Network 中出现 307 到 Blob 的请求，Blob 返回 206；
   - 关闭再打开，不产生新的 provider 请求（看服务端日志）。
3. 对一个历史 `expired` job：
   - tile 显示“已过期”；
   - 弹层显示“原始视频已过期”，没有 500，没有黑屏。
4. 删除/停用 provider 凭证，模拟 `failed`：
   - job 进入 `failed`，按 backoff 重试；
   - 前端显示“归档失败，稍后重试”。
5. 回归图片：缩略图、预览、圈选编辑、Compare models 全部正常。

---

## 12. 分阶段落地

### P0（0.5–1 天）：止血 + 可诊断

- `MediaJobContentController` 捕获 `MediaArtifactUnavailableException`，返回 410 + 明确错误码，不再 500。
- 前端 `<video>` 加 `onError`，显示“视频不可用/已过期”，并保留“在新标签打开”。
- 列表 tile 对 `artifactState=expired` 显示“已过期”。

**收益**：用户不再看到黑屏和 500；问题可被正确解释。

### P1（2–3 天）：完成时物化 + 307（对象存储 Range）

- `MediaJob` 字段 + 迁移 + 索引。
- `MediaArtifactService`。
- `MediaArtifactMaterializationJob` + `GatewayModule` 注册。
- `MediaJobContentController` / `GatewayVideoController` 走新逻辑。
- `MediaJobView` / 前端类型。
- `get_video_status` 热路径 linker。

**收益**：新生成的视频永久可播；历史 job 自动标记；列表/弹层拿到 `playable`。

### P2（1–2 天）：海报 + 预览体验

- JCodec 海报生成（或 ffmpeg 备选）。
- 列表海报 + 时长。
- `VideoPlayer` 组件、两栏弹层、键盘快捷键、静音自动播放。
- 列表/弹层轮询。

**收益**：从“能看”到“好用”。

### P3（可选）：运维与成本

- 保留期清理 Job。
- 指标/告警。
- CDN / 公共容器。
- 视频重跑入口（对 `expired` job 用原 prompt 重新生成）。

---

## 13. 风险与备选方案

### 13.1 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 物化下载很慢/超时 | job 卡在 `storing` | 租约 10 分钟回收；HTTP 超时 5 分钟；后台 batch 限制 3 |
| provider 限流 | 批量回填失败 | 每 tick 3 条、指数退避、`created_at` 升序优先救新 |
| 存储成本增长 | 账单上升 | 90 天保留 + 引用检查；只存 10 秒 MP4，不转码 |
| 海报生成失败 | 列表无海报 | 不阻塞 `stored`；退回黑色 Play tile |
| 多副本重复下载 | 浪费带宽 | Mongo 条件 claim + 租约；热路径 linker 先关联 |
| 对象存储未配置 | 307 不可用 | Mongo fallback + 全量 200（可播放、无 seek）；部署文档建议配置对象存储 |
| 迁移把旧 job 全部标 pending | 启动后大量回填 | batch + 退避 + 只处理 7 天内/最近 119 条；可配置开关 |

### 13.2 备选方案

**A. 直接 302 到上游 URL**

- 拒绝：Gemini 需要 Bearer token；Ark/KIE URL 会过期；浏览器可能遇到 CORS/签名问题；无法审计。

**B. 首次预览时缓存**

- 拒绝：首次预览仍然慢；用户可能永远不打开，等想起来时上游已经过期；并发首开可能重复下载。
- 可作为 P1 的懒加载补充，但不能替代完成时物化。

**C. 只存上游 asset URL，不存字节**

- 拒绝：`upstream_asset_url` 也会过期，且 Gemini 的 `uri` 可能需要认证；无法保证长期可播。

**D. 视频 base64 存 Mongo**

- 拒绝：10 MB 视频变成 ~13 MB base64，Mongo 文档上限 16 MB，且读放大严重；对象存储是正确介质。
- 小部署无对象存储时，`FileService` 现有 fallback 已经提供 base64，但只作为兜底。

**E. HLS 转码**

- 拒绝：10 秒 MP4 渐进式播放足够；转码引入 CPU/存储/任务队列复杂度。长视频/多码率场景再考虑。

**F. 前端用 Blob URL 下载完整视频**

- 拒绝：`ArtifactDrawer` 已经这么做（`state.fileBlobUrl`），但那是小文件/已下载场景；生成页 20 条视频不适合全量拉取，且无法 seek 未下载部分。

---

## 14. 代码改动清单

### 服务端

| 文件 | 改动 |
|---|---|
| `core-ai-server/src/main/java/ai/core/server/domain/MediaJob.java` | 新增 artifact/poster/duration 字段 |
| `core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationVMediaJobArtifact.java` | 新增迁移 + 索引 |
| `.../domain/migration/SchemaMigrationManager.java` | 注册迁移 |
| `.../gateway/MediaJobService.java` | `createVideoJob`/`updateVideoStatus` 状态；`link`/`markStored`/claim 方法 |
| `.../gateway/MediaArtifactService.java` | 新增：下载、存储、海报、错误分类 |
| `.../gateway/MediaArtifactMaterializationJob.java` | 新增定时 Job |
| `.../gateway/MediaArtifactUnavailableException.java` | 新增异常 |
| `.../gateway/GatewayModule.java` | 注册 Job；`MediaJobService` 实现 linker |
| `.../gateway/MediaJobContentController.java` | 已存储 307；未存储 409；过期 410 |
| `.../gateway/GatewayVideoController.java` | 下载即归档，直接返回字节（不 307） |
| `.../gateway/MediaJobWebServiceImpl.java` | view 字段 + `get(id)` |
| `.../gateway/GatewayMediaProvider.java` | 404/410 → `MediaArtifactUnavailableException` |
| `core-ai-api/.../media/MediaJobView.java` | 新增 view 字段 |
| `core-ai-api/.../media/MediaJobWebService.java` | 可选：新增 `get(id)` 详情接口（也可用 HTTP route 实现） |
| `.../artifact/ServerImageOutputSink.java` | 可选 linker，保存视频后关联 |
| `.../artifact/MediaArtifactLinker.java` | 新增窄接口 |
| `.../run/AgentRunBuilder.java` / `.../session/SessionContextBuilder.java` | 注入 linker |
| `core-ai-server/build.gradle.kts` | 可选：JCodec 依赖（海报） |

### 前端

| 文件 | 改动 |
|---|---|
| `core-ai-frontend/src/api/client.ts` | `MediaJob` 新字段 |
| `core-ai-frontend/src/pages/generations/Generations.tsx` | 列表 tile、弹层、轮询 |
| `core-ai-frontend/src/components/VideoPlayer.tsx` | 新增播放器组件 |
| `core-ai-frontend/src/components/VideoPlayer.module.css` 或 Tailwind | 播放器样式 |

### 文档

- 本文件。
- 如果实现，补充 `docs/cn/media-generation-trace-cost.md` 里“视频产物链接”的说明（当前视频取 `/api/media-jobs/{id}/content`，归档后应取 `/api/files/{file_id}/content`）。

---

## 15. 附录：现场排查命令

### 15.1 看容器日志里的视频错误

```powershell
docker logs core-ai-server 2>&1 |
  Select-String -Pattern "media-jobs|video download|Requested entity|response_code=500" |
  Select-Object -Last 80
```

### 15.2 统计视频 job 与 file_id

```powershell
docker exec core-ai-mongo mongosh 'mongodb://localhost:27017/core-ai?replicaSet=rs0&directConnection=true' --quiet --eval '
db.media_jobs.aggregate([
  {$group:{_id:{media_type:"$media_type", provider_id:"$provider_id", has_file:{$ne:["$file_id",null]}}, count:{$sum:1}}}
]).toArray()'
```

### 15.3 看某条 job 的完整字段

```powershell
docker exec core-ai-mongo mongosh 'mongodb://localhost:27017/core-ai?replicaSet=rs0&directConnection=true' --quiet --eval '
db.media_jobs.findOne({_id:"361f2e56-7bc6-4375-b0be-6177ef3cf655"})'
```

### 15.4 看是否已有保存过的视频文件

```powershell
docker exec core-ai-mongo mongosh 'mongodb://localhost:27017/core-ai?replicaSet=rs0&directConnection=true' --quiet --eval '
db.file_records.find({content_type:"video/mp4", file_name:/^video-/},{_id:1,file_name:1,size:1,created_at:1}).sort({created_at:-1}).limit(10).toArray()'
```

### 15.5 直接验证内容端点（登录后带 Cookie）

```powershell
curl.exe -I "https://localhost:8443/api/media-jobs/361f2e56-7bc6-4375-b0be-6177ef3cf655/content" -k
curl.exe -H "Range: bytes=0-1023" -D - -o NUL "https://localhost:8443/api/media-jobs/<stored-job-id>/content" -k
```

预期（改造后）：

- 已存储：`307` + `Location: https://<account>.blob.core.windows.net/...`；跟随重定向后由 Blob 返回 `206` / `Accept-Ranges: bytes`。
- 未存储：`409` + `Retry-After: 2` + `{"errorCode":"MEDIA_ARTIFACT_NOT_READY"}`。
- 已过期：`410` + `{"errorCode":"MEDIA_ARTIFACT_EXPIRED"}`。

---

## 16. 结论

这次“视频看不了”不是播放器兼容性问题，也不是 Docker 网络问题，而是**平台把视频当成上游的临时资产来用**：只存了 `upstream_video_id`，没有存字节；预览时回源，上游过期后 404 被包装成 500。图片之所以正常，是因为图片在生成时就落库了。

修复的关键不是给 `<video>` 加一个 `onError`，而是把视频产物纳入和图片一样的“生成即归档”生命周期；在此基础上再做 Range 播放、海报和状态化预览 UI。这样既能解决当前故障，也能避免下一次“昨天还能看、今天打不开”。

---

## 17. 实现记录（2026-11-03）

已落地 P1 核心 + 前端预览：

- `MediaJob` 新增 `artifact_state / artifact_error / artifact_size / artifact_stored_at / artifact_attempts / artifact_next_attempt_at / artifact_claimed_at / poster_file_id / poster_attempted_at / duration_seconds`。
- 新增 `MediaJobArtifactStore`：claim、store、expired/failed、重试退避、回填查询。
- 新增 `MediaArtifactService`：首次访问时同步下载并写入 `FileService`；404/410 标记 `expired`。
- 新增 `MediaPosterService`：视频入库后用 JCodec 抽第 1 秒（失败退回第 0 秒）的一帧，缩放到 720px 内并编码为 JPEG，写入 `FileService` 后回填 `poster_file_id`；后台每分钟回填 2 条缺海报的已存储视频。JCodec 是纯 Java，不依赖服务器安装 ffmpeg。
- 新增 `MediaArtifactMaterializationJob`：每 60 秒轮询未终结视频（20 条/次），物化 3 条已完成但没有 `fileId` 的视频，并回填 2 条缺海报的视频。
- `MediaJobContentController`：已存储走 307；未存储首次访问同步物化；并发归档返回 409；上游已过期返回 410。
- `GatewayVideoController.content`：下载即归档，并**直接返回字节**（兼容 Desktop/CLI 不跟随重定向的旧客户端）。
- `MediaJobView` 新增 `artifactState / artifactError / artifactSize / playable / contentUrl / posterFileId / posterUrl / durationSeconds`。
- 前端新增 `VideoPlayer`：默认显示封面 + 播放按钮，**点击后才开始播放**，不再自动播放；加载/归档中/已过期/归档失败/可播放五种状态，静音播放、开启声音、错误自动重试 3 次；列表 tile 使用视频封面、时长和“已过期”标识；列表增加 Refresh 按钮。

刻意未做/后续项：

- **海报已实现**：`MediaPosterService` 用 JCodec 抽帧并生成 JPEG 海报，`poster_file_id` / `posterUrl` 已回填；列表和弹层都以封面为入口，点击才播放。JCodec 不支持的编码会让海报保持空，但不会影响视频归档。
- **server 侧 agent 的 `get_video_status` 保存路径通过文件名反查关联**：`MediaJobArtifactStore.findSavedVideo()` 在下载前按 `video-<gateway handle>.mp4`（同时兼容 legacy handle）查询 `file_records`，命中后直接 `markStored`，避免二次下载，也让上游过期后仍能播放已保存的文件。未来可升级为 sink 主动回调的 `MediaArtifactLinker`，省掉反查。
- **没有新增 `GET /api/media-jobs/:id` 详情接口**：当前弹层直接用列表快照，`VideoPlayer` 通过内容端点和自动重试处理归档状态；如果要做进度条/自动轮询，再补详情接口。
- **应用内 206 fallback 未实现**：core-ng 的 `HTTPStatus` 没有 206/416；无对象存储时只能返回 200 全量（可播放、不能 seek）。生产已配置 Azure Blob，307 路径支持 Range。
- **数据迁移只加索引，不批量改数据**：回填查询把 `artifact_state` 为 null 的已完成视频视为 `pending`，由定时 Job 按 `completed_at` 升序处理；这样不需要一次性写 119 条记录。
- 本地已验证：`:core-ai-server:compileJava`、`:core-ai-server:test`（全量，含 `MediaPosterServiceTest`）、checkstyle、PMD、SpotBugs、`installDist`、前端 `npm run build`，以及用 scratch Mongo 启动新 server 到 `startup completed`。
