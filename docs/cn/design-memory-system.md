# Memory改造V1

## 1、整体流程说明

### 文件处理流程

Session -> daily-logs ->episodes ->knowledge

| 层         | 文件结构                                             | 位置                | 内容                                                         | 新增功能 |
| ---------- | ---------------------------------------------------- | ------------------- | ------------------------------------------------------------ | -------- |
| session    | {项目文件名}/cli-{time}.data                         | ~/.core-ai          | 原始用户对话                                                 | 否       |
| daily-logs | daily-logs/{date}/{taskName}.md                      | {项目文件}/.core-ai | 定期从session 中，总结相关任务，以任务日志的形式存在，重点记录 ：处理的任务描述、任务过程、agent遇到的障碍与以应对、用户反馈与应对、任务结果 | 是       |
| episodes   | episodes/{date}.md                                   | {项目文件}/.core-ai | 按日汇总的，任务详情索引表。记录任务描述、对应daily-logs     | 是       |
| knowledge  | Memory.md` + `log.md` + `knowledge/{type}/{name}.md` | {项目文件}/.core-ai | 三个文件，Memory.md作为知识索引（现在200条），log.md(只做的knowledge层变跟日志)，`knowledge/{type}/{name}.md   wiki 形式管理的知识详情 | 是       |

### 触发方式

T1（independent agent）: 根据用户对话情况，每10轮会话（或3分钟没有新的操作）尝试提取记忆。

T2:(main agent) 在主对话框，用户直接要求记录相关历史，整理成记记忆

T3:(session close agent)   快速总结session 要点 -》写入  daily-logs ，并记录相关daily-logs 未提取知识，下次启动时 通过T1触发处理未提取知识



### 触发Agent设计原则：

1、复用主agent  systemprompt 和messagelist ,在缓存的基础进行提取。

2、independent agent 的处理不做消息持久化，只记录处理日志

3、轮次限制最多10轮，避免异常



### knowledge层更新通知机制

1、Knowledge 更新会将变跟日志  log.md （追加部分），同时通知到 main agent 。

​	只通知，不更新systemprompt，避免缓存失效

```
  // Message.java 新增
  @Property(name = "hidden")
  public boolean hidden;
```

2、Resume ，systemprompt 部分重建，就会载入新的 Memory.md，更新message list 





## 2、各层详细文件结构

### 2.1 daily-logs

```
---
task: grubhub.com 菜单爬虫开发
date: 2026-03-28
result: grubhub_menu.py，11 分类，95 菜品，耗时约 20s
---

## 障碍 & 应对
| 障碍                        | 应对方式                                        |
|-----------------------------|------------------------------------------------|
| networkidle 超时             | 改用目标元素等待                                 |
| 虚拟化渲染，DOM 数据不完整    | 放弃 DOM 抓取，改为 API 拦截                     |
| feed API 返回骨架空数据       | 遍历点击分类 tab 触发懒加载                       |

## 用户反馈
验证结果正确，数据格式符合预期。

## 关键决策
- 放弃 DOM 抓取，优先 API 拦截（SPA 虚拟化渲染不可靠）
- headless=False，反爬效果最佳
```



### 2.2 episodes

```
| File                                          | Description                                                  | Created  | Updated  |
| --------------------------------------------- | ------------------------------------------------------------ | -------- | -------- |
| daily-logs/2026-03-28/grubhub_menu_crawler.md | 进行 grubhub.com 菜单爬虫开发，获取 11 分类 95 菜品。关键用户反馈：验证结果正确。 | 10:02:03 | 11:02:01 |

```



### 2.3 knowledge

完整目录结构

```
.core-ai/
├── knowledge/                     
    ├── Memory.md                  ← 索引 · 每个知识wiki一行摘要(cli 上限200条) · 始终加载
    ├── log.md                     ← 操作时间线  append-only 
    ├── project/                   ← 项目知识
    │   ├── spa-scraping.md
    │   ├── two-phase-intercept.md
    │   └── crawler-comparison.md
    ├── user/                      ← 用户画像
    │   └── profile.md
    ├── feedback/                  ← 反馈与纠正
    │   └── crawler-behavior.md
    └── reference/                 ← 外部参考
        ├── grubhub-api.md
        └── nodriver.md

```

#### Memory.md(参考结构，内容不参考)

```
## project

| File | Description | Created | Updated |
|------|-------------|---------|---------|
| feedback-coding-conventions.md | feedback memories — coding conventions best practices | 2024 | 2024-12-01 |
| feedback-memory-management.md | feedback memories — memory management best practices | 2024 | 2026-04-28 |
| user-coding-conventions.md | user memories — coding conventions and output format preferences | 2024 | 2026-04-13 |
| project-self-improvement.md | project memories — self-improvement system architecture | 2024 | 2026-04-13 |
## user
| File | Description | Created | Updated |
|------|-------------|---------|---------|
| feedback-coding-conventions.md | feedback memories — coding conventions best practices | 2024 | 2024-12-01 |
| feedback-memory-management.md | feedback memories — memory management best practices | 2024 | 2026-04-28 |
| user-coding-conventions.md | user memories — coding conventions and output format preferences | 2024 | 2026-04-13 |
| project-self-improvement.md | project memories — self-improvement system architecture | 2024 | 2026-04-13 |

## feedback

## reference
```

#### log.md (只保留最新30天)

以tool 的形式对外提供添加和删除，添加

​		addknowledgelog(String logInfo)

参数格式：

```
## [2026-03-29] lint | 周健康检查
- 发现: project/spa-scraping.md 与 reference/nodriver.md 矛盾 refs:[knowledge/reference/nodriver.md](confuse)
- 修复: 统一 headless 策略描述
```



添加后文件

```
## [2026-03-28] ingest | grubhub 菜单爬虫
- 新增: reference/grubhub-api.md
- 更新: project/spa-scraping.md (5 条新踩坑)
- 更新: user/profile.md (结果验证偏好)
- 更新: Memory.md

## [2026-03-28] query | SPA 爬虫最佳实践
- 综合 3 个 wiki 页面生成对比分析
- 结果写回: project/crawler-comparison.md

## [2026-03-29] lint | 周健康检查
- 发现: project/spa-scraping.md 与 reference/nodriver.md 矛盾 refs:[knowledge/reference/nodriver.md](confuse)
- 修复: 统一 headless 策略描述
```

deleteknowledgelog(String logName)

```
## [2026-03-28] ingest | grubhub 菜单爬虫
```



最终文件：

```
## [2026-03-28] query | SPA 爬虫最佳实践
- 综合 3 个 wiki 页面生成对比分析
- 结果写回: project/crawler-comparison.md

## [2026-03-29] lint | 周健康检查
- 发现: project/spa-scraping.md 与 reference/nodriver.md 矛盾 refs:[knowledge/reference/nodriver.md](confuse)
- 修复: 统一 headless 策略描
```



#### Wiki Page 格式

Md 形式存在非结构化知识页面，页面内容通过指定的标志处理引用和溯源。

正文内通过 `refs:[uri]` 标记外部 Wiki 引用，`refs:[uri](confuse)` 标记引用间矛盾，`source:[相对路径]` 标记来源（daily-logs）。

```
# SPA 爬虫策略

## 核心原则
对 SPA 站点，优先拦截 Network API，避免 DOM 抓取。
DOM 在虚拟化渲染下不可靠；API 响应更稳定且包含完整数据。

## 踩坑与解法
### networkidle 超时 source:[daily-logs/2026-03-28/grubhub_menu_crawler.md] source:[daily-logs/2026-04-10/doordash_menu_crawler.md]
- 现象: Timeout 30000ms，SPA 持续后台请求
- 解法: 改用目标元素出现作为就绪信号 → [[nodriver]]
- 验证: 2/2 站点有效 ✓

### Virtual Scroll 数据丢失 source:[daily-logs/2026-03-28/grubhub_menu_crawler.md]
- 现象: 滚动后 section 数量骤减
- 解法: 放弃 DOM，改为 API 拦截

source:[daily-logs/2026-03-28/grubhub_menu_crawler.md]
source:[daily-logs/2026-04-10/doordash_menu_crawler.md]
refs:[knowledge/reference/nodriver.md](confuse): headless 策略在 Grubhub 有效但 Doordash 检测 headless，结论冲突
```

