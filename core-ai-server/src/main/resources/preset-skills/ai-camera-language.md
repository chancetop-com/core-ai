---
name: ai-camera-language
description: 大师运镜词典：24 种电影运镜的提示词写法与情绪用途（基础六式/招牌运镜/复合公式），含运镜动机、dolly 与 zoom 之辨、节奏纪律。适用于任何视频生成的运镜描述。
---

# 大师运镜词典（Camera Language）

专业平台"大师运镜"预设的提示词等价物。

## 四条铁律

1. **运镜词放提示词句首**（"Pan right across the beach…"而不是"…camera pans right"）——命中率显著更高；
2. **一镜只用一种运镜**：叠加（pan+tilt+zoom）大概率混乱；复合运镜要么拆成两个镜头，要么写成分拍的顺序动作（"cranes down, then pushes in"）；
3. **运镜必须有动机**：揭示、跟随、压缩、释放、孤立、失稳、转移注意力，七者之一——没有动机就用静止镜；
4. **dolly 不是 zoom**：dolly 是机身物理移动（前后景有视差），zoom 是镜头裁切（画面发平）。要空间感就写 `physical dolly, parallax` 或 `no zoom — physical dolly only`；要光学效果才写 zoom。

## 基础运镜（推拉摇移跟甩 + 升降）

| 预设 | 提示词 | 情绪/用途 |
|---|---|---|
| 缓推 | slow push-in / dolly in | 压迫、聚焦、进入内心 |
| 缓拉 | slow pull-back / dolly out | 释放、孤立、揭示环境 |
| 摇镜 | slow pan left/right, tilt up/down | 巡视空间、建立关联 |
| 横移 | truck left/right, lateral tracking | 横向空间、平行跟拍 |
| 跟随 | tracking shot following subject | 陪伴、行进 |
| 甩镜 | whip pan to [目标] | 快节奏转场、惊变 |
| 升降 | crane up / crane down | 开场落场、命运感 |
| 静止 | static locked shot | 对白、留白、凝视 |

## 招牌运镜（一集限量使用）

| 预设 | 提示词 | 情绪/用途 |
|---|---|---|
| 英雄环绕（贝氏） | low angle hero orbit, 360 orbit around subject, slow motion | 英雄登场、觉醒、高潮转折 |
| 微距穿梭 | extreme macro shot, 100mm macro, shallow depth of field, camera glides through | 片头定调、关键道具揭示 |
| 摇臂揭示 | slow crane up revealing [环境], dramatic height change | 定场镜、规模揭示、片尾升华 |
| 希区柯克 | dolly zoom (push in while zooming out) | 眩晕、认知崩塌 |
| 疾推变焦 | crash zoom in | 震惊、发现（钩子镜利器） |
| 揭示镜头 | camera slides out from behind [遮挡物] to reveal | 转角发现、反转 |
| 过肩 | over-the-shoulder shot | 对话、建立关系 |
| 主观 | first-person POV | 恐惧、醉酒、代入 |
| 荷兰角 | dutch angle, tilted horizon | 失稳、精神异常 |
| 子弹时间 | bullet-time orbit, frozen moment | 奇观、定格瞬间 |
| 穿越 | FPV drone flythrough, fly through aperture | 空间穿越、大场景 |
| 移动延时 | hyperlapse, moving time-lapse | 时间压缩、变迁 |
| 手持 | handheld camera, slight shake | 纪实感、不安、追逐 |
| 升格 | slow motion, 120fps look | 情绪峰值、动作高光 |
| 变速 | speed ramp from slow to fast | 打斗、反转揭示 |

## 复合运镜公式

| 公式 | 组成 | 用途 |
|---|---|---|
| 英雄时刻 | 环绕 + 缓升 + 推近 | 主角觉醒、气场建立 |
| 人物出场 | 背跟 + 升镜 | 开场引出主角 |
| 反转揭示 | 前景遮挡 → 横移/升镜 → 揭示 | 信息揭露、惊喜 |
| 情绪递进 | 全景 → 中景 → 近景 → 特写（同方向连续推进） | 情绪层层收紧 |

## 写法要点

- **写速度与时长**：`slow dolly in, 4 seconds, constant speed` 比裸写 dolly in 稳；3-5 秒慢动作最可靠，甩镜/急推 2-3 秒最稳；
- **起幅落幅明确**：写清起幅与落幅的景别（"from wide to close-up on face"），模型才知道运动终点；
- **运动要有止**：build → accelerate → hold → brake 的节奏词（"brake into a close-up"）能消除 AI 匀速漂移感；
- **竖屏少用 wide pan**：画面比例影响运镜选择，9:16 优先推拉与跟拍。

## 短视频节奏提示

- crash zoom 与 whip pan 是"停住滑动手指"的两大利器，前 3 秒钩子镜优先考虑；
- dolly zoom / rack focus 是观众"说不出名字但感受得到"的高级感来源，一集用一次即可；
- hero orbit（贝氏英雄环绕）是最高光镜头，一集只用一次、只给主角；
- crane reveal 默认用于开场定场与片尾升华两个位置；
- 微距穿梭适合片头 logo/道具悬念镜，不适合连续两个镜头使用。
