# 2026-11-24 Dev E2E — Skill editor quality precheck (2.0.347)

## 环境

- Server: `https://core-ai-server.connexup-dev.net`（namespace `dev-ai`）
- Image: `chancetop/core-ai-server:2.0.347`
- CI: `Build Server` run `45317716668`（commit `50aee8a2d`），构建成功
- 运行 pod：2/2 Ready，两个 pod 的 `imageID` 均为
  `docker.io/chancetop/core-ai-server@sha256:99ea90343f6c70848e1ffb94af861f428aeb9879de65b2bea345314eb9e85de4`，
  与 `docker buildx imagetools inspect chancetop/core-ai-server:2.0.347` 的 OCI index digest 一致
- 部署方式：`kubectl -n dev-ai set image deploy/core-ai-server core-ai-server=chancetop/core-ai-server:2.0.347`；
  `fbr-env-project` 的 dev manifest 未改动（该文件在工作区有其他会话的未提交修改）

## 范围

新增 `POST /api/skills/:id/quality-checks`：用编辑器草稿（content / description / allowed_tools /
resources，与 `PUT /api/skills/:id` 同形）执行与保存相同的 candidate 构建、metadata 一致性与质量门禁，
返回 typed report，但不写 skill、不写 blob、不 bump revision。前端 SkillEditor 增加 “Check quality” 按钮；
质量报告面板的问题列表改为固定最大高度的可滚动区域。

## 结果

测试 fixture：临时上传 `Stephen/precheck-e2e-112411`（有效 SKILL.md + `README.txt` + `eval/eval.json`，
8 个 eval 维度），测试后删除。

| 用例 | 请求 | 结果 |
|---|---|---|
| 有效草稿预检 | content 变更（`metadata.version` 1.0.0→1.0.1）+ `## Draft note`；resources 全部 `keep` | HTTP 200，`gate_result=PASSED`，`warn_count=0`，无 issues |
| 预检无写入 | 对比预检前后的 `GET /api/skills/:id/download` 与 `GET /api/skills/:id` | `content`、`digest`、`updated_at` 均未变化 |
| 缺少 eval 的草稿预检 | resources 只保留 `README.txt` | HTTP 200，`gate_result=REJECTED`，issue `SKILL.EVAL_DATASET:FAIL` |
| 预检不放行保存 | 用同一份 REJECTED 草稿执行 `PUT /api/skills/:id` | HTTP 422，`errorCode=SKILL_QUALITY_REJECTED`，错误体带 `quality_report.gate_result=REJECTED`；skill 内容未变化 |
| 清理 | `DELETE /api/skills/:id` | 删除成功 |

额外观察：首次用「仅追加正文、未 bump `metadata.version`」的草稿预检时返回
`SKILL.METADATA_REQUIRED:FAIL`（内容变化必须递增 version），说明预检复用了与保存相同的版本规则。

## UI 验证

无法用 dev 的邮箱/密码登录控制台，故未在浏览器里点击按钮。已确认 dev 已发布的前端产物包含本次改动：
`GET https://core-ai-server.connexup-dev.net/assets/SkillEditor-C4bCRNLO.js` 返回 200，且包含
`Check quality` 与 `precheck — nothing saved` 字符串。面板可滚动与 precheck 提示由
`QualityReportPanel.test.tsx` 覆盖。

## 回归

- `:core-ai-server:check`：通过（checkstyle / PMD / SpotBugs / 2091 tests）
- `npm --prefix core-ai-frontend run build`：通过（tsc + vite）
- `QualityReportPanel.test.tsx`：2/2 通过
