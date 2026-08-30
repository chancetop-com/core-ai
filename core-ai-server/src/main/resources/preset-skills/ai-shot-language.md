---
name: ai-shot-language
description: 镜头语言词典：九档景别、机位角度、人物朝向与正反打、构图法则的提示词写法与叙事用途，含景别节奏规律与关键帧八模块模板。适用于写分镜 framing 字段与关键帧生成。
---

# 镜头语言词典（Shot Language）

专业平台"景别/机位"预设的提示词等价物。一个镜头 = **景别 + 机位 + 运镜 + 光线**四轴齐全；运镜见 ai-camera-language，光线见 ai-lighting-looks，本 skill 管前两轴与构图。

## 三轴铁律

每个镜头景别、机位、运镜三轴必须有值——缺任何一轴，模型就替你乱决定：
- 景别管"画面装多少"：`wide shot / medium shot / close-up`；
- 机位管"从哪看"：`eye level / low angle / over-the-shoulder`；
- 运镜管"动不动"：见 ai-camera-language；不动要显式写 `static locked shot`。

## 景别词典（从远到近，按人物切线）

| 景别 | 提示词 | 叙事用途 |
|---|---|---|
| 大远景 | extreme wide shot / establishing shot | 交代世界与地点，人如一点，环境是主角 |
| 远景 | wide shot / long shot, full body visible | 人物与环境关系、角色登场 |
| 全景 | full shot | 全身动作、服装站位、立人物 |
| 中全景 | medium wide shot / cowboy shot, framed from the knees up | 动作准备、对峙 |
| 中景 | medium shot, waist up | 对话主力档位（全片约六成镜头） |
| 中近景 | medium close-up, chest up | 情绪开始集中，肩线体态仍在 |
| 近景 | close-up / face focus | 情绪爆点，全靠表演撑 |
| 大特写 | extreme close-up, eye focus / detail shot | 线索、钩子、反转点 |
| 微距/插入 | macro shot / insert shot | 道具细节：手机屏幕、戒指、伤口 |

- 景别放句首、主体之前（模型对靠前词权重更高）；
- 与焦段景深同给才够约束：`close-up, 85mm, shallow depth of field`；
- 切线写不清就用切位描述：`framed from the knees up`；
- 特写必须写清"拍什么"：面部特写 / 手部特写 / 信件特写 / 眼神特写，不是泛泛 close-up。

## 机位角度词典

| 角度 | 提示词 | 心理效果 |
|---|---|---|
| 平视 | eye level shot | 平等、中立、日常 |
| 仰拍 | low angle shot, looking up at character | 强大、权威、英雄/反派气场 |
| 俯拍 | high angle shot, looking down at character | 渺小、脆弱、被压制 |
| 鸟瞰 | overhead / top-down / bird's-eye view | 上帝视角、宿命、全局调度 |
| 贴地仰 | worm's-eye view, extreme low angle | 极致压迫、怪物威胁、史诗登场 |
| 荷兰角 | dutch angle, tilted frame | 不安、混乱、精神失衡 |
| 过肩 | over-the-shoulder shot | 对话空间、观察关系 |
| 主观 | POV shot, first-person view | 代入、恐惧、隐藏信息 |

权力反转：开场 A 仰拍强势、B 俯拍弱势，结尾对调——角度本身就是剧情。

## 人物朝向与正反打

| 朝向 | 提示词 | 用途 |
|---|---|---|
| 正面 | front view, facing the camera | 直面观众、展示 |
| 3/4 侧面 | three-quarter view | 最经典肖像角度 |
| 纯侧面 | profile view / side view | 正反打、剪影 |
| 背影 | back view, from behind | 悬念、离去、隐藏表情 |

正反打对齐四件套（对谈镜头两镜必须成对写，缺一项就穿帮）：
1. 视线相反：正打 `looking frame right`，反打 `looking frame left`；
2. 机位同高：两镜都 `eye level`；
3. 站位不越轴：正打 `character on the left`，反打 `character on the right`；
4. 前景肩：两镜都 `shoulder in foreground, blurred`。

## 构图法则

| 法则 | 提示词 | 效果 |
|---|---|---|
| 三分法 | rule of thirds, subject on right third | 平衡，电影感默认 |
| 引导线 | leading lines toward subject | 引导视线 |
| 框内框 | frame within frame | 窥视感、层次 |
| 对称 | symmetrical composition | 秩序、仪式感 |
| 负空间 | negative space around subject | 孤立、脆弱 |
| 视线留白 | looking room, headroom | 人物看向处留空 |

## 景别节奏

- 漏斗形推进（经典）：establishing/wide → medium 带进对话 → OTS 承接 → 情绪转折才给 close-up——"切进去"；
- 从紧到宽 = 释放/抛弃：特写拉到大全景读作"个人在世界中的位置"；
- 全景直切大特写突兀（除非刻意震惊）；连续两镜同景别同机位是剪辑事故；
- 正反打情绪递进：A中景 → B中近景 → A近景 → B特写——景别随情绪收紧。

## 关键帧八模块模板

写关键帧 prompt 按八模块填，缺一不可：

1. 剧情任务（purpose：戏剧目的，不是画面描述）
2. 固定角色（引用角色设定表，不重新描述）
3. 场景环境（地点/时段/背景元素）
4. 动作与情绪（具体连续动词 + 表情细节）
5. 景别与构图（景别 + 机位 + 朝向 + 构图法则）
6. 光线与色调（引用 ai-lighting-looks 预设）
7. 画风词（引用 styleBible，全片锁定）
8. 负面限制（不换脸/不换发型/无多余人物/不夸张表情）

组合公式：`[景别] + [机位] + [朝向] + [主体] + [动作] + [光学] + [光线]`。

## 纪律

- 每镜必有 purpose——没有戏剧目的的镜删掉，不是渲染；
- 景别是叙事选择不是术语堆砌：开场要远、对话要中、情绪要近、线索要特写；
- 构图拿不准先走 ai-contact-sheet 九宫格选版，别为试错烧视频钱。
