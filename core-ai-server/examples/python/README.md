# 用 Python 调用 Core AI Gateway

使用 OpenAI Python SDK，默认连接 UAT 的 `deepseek-flash`：

- Base URL：`https://core-ai-server.connexup-uat.net/api/gateway/v1`
- 鉴权：Core AI 用户 API Key，SDK 自动发送 `Authorization: Bearer ...`
- 模型列表：`GET /api/gateway/v1/models`
- 普通对话：`POST /api/gateway/v1/chat/completions`

## 安装和运行

在本目录运行（Python 3.9+）：

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python gateway_chat.py
```

按提示输入 Key，输入内容不会回显。脚本不保存 Key。
也支持从环境变量 `CORE_AI_API_KEY` 读取，便于集成到已有应用。

```bash
# 查看当前环境发布的模型
python gateway_chat.py --list-models

# 指定模型和问题
python gateway_chat.py --model deepseek-flash --prompt "用一句话解释什么是 API Gateway。"
```

可以通过 `--base-url` / `CORE_AI_BASE_URL` 覆盖完整 Gateway 地址，
通过 `--model` / `CORE_AI_MODEL` 覆盖模型。
不同环境的 Key 不一定通用。模型列表包含图像、视频等模型，聊天应选支持 Chat 的模型。

## 集成到自己的代码

```python
import os
from openai import OpenAI

with OpenAI(
    base_url="https://core-ai-server.connexup-uat.net/api/gateway/v1",
    api_key=os.environ["CORE_AI_API_KEY"],
    timeout=90.0,
    max_retries=0,
) as client:
    response = client.chat.completions.create(
        model="deepseek-flash",
        messages=[{"role": "user", "content": "请只回复：Gateway Python 调用成功。"}],
        stream=False,
    )
    print(response.choices[0].message.content)
    print(response.usage)
```

`base_url` 必须包含 `/api/gateway/v1`；不需要自行拼接 `/chat/completions`。

## 实际验证

2026-09-21，Python 3.13.0 / openai 2.38.0，使用已有 UAT 用户 Key：

- 模型列表请求成功，返回 21 个模型。
- 普通对话使用 `deepseek-flash`，返回 `Gateway Python 调用成功。`。
- 首次成功响应 ID：`38517c34-85b5-4bb4-b67a-13c1a34d469f`。
- `finish_reason=stop`，输入 40 tokens，输出 28 tokens，合计 68 tokens。

模型配置、输出和 token 数量会随运行变化。

### 当前实时流式接口的兼容性

本示例使用已验证的普通对话。额外测试发现，在 UAT 使用
`stream=True` 和 `Accept: text/event-stream` 时，HTTP 返回 200，
但响应以 `retry: 5000\n\n` 控制帧开头。openai 2.38.0 会把该帧的空 data
作为 JSON 解析，触发 `JSONDecodeError`；已用实际响应和 SDK 本地解码复现。

服务端对应 `core-ai/src/main/java/ai/core/sse/internal/PatchedServerSentEventHandler.java`
中的浏览器重试帧。省略自定义 Accept 的 `stream=True` 会经过服务端缓冲，
不能视为实时流式。本次没有修改或部署服务端。
