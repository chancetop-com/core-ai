# LLM Provider Configuration

core-ai-cli supports multiple LLM providers. Configure via `agent.properties`.

## Supported Providers

| Provider | Key | Base URL Required? |
|----------|-----|--------------------|
| LiteLLM | `litellm` | Yes |
| OpenAI | `openai` | Yes (custom endpoint) |
| Azure OpenAI | `azure` | Yes |
| OpenRouter | `openrouter` | No (hardcoded) |
| DeepSeek | `deepseek` | No (hardcoded) |

Auto-detection: a provider is activated when its `<provider>.api.key` is set.
For `litellm`, `openai`, and `azure`, `<provider>.api.base` is also required.

## Minimal Examples

### LiteLLM
```properties
litellm.api.base=https://litellm.your-company.net
litellm.api.key=sk-your-key
litellm.model=gpt-4o
```

### OpenAI
```properties
openai.api.base=https://api.openai.com/v1
openai.api.key=sk-your-key
openai.model=gpt-4o
```

### DeepSeek
```properties
deepseek.api.key=sk-your-key
deepseek.model=deepseek-chat
```

### OpenRouter
```properties
openrouter.api.key=sk-your-key
openrouter.model=anthropic/claude-sonnet-4
```

### Azure OpenAI
```properties
azure.api.base=https://your-resource.openai.azure.com
azure.api.key=your-key
azure.model=gpt-4o
```

## Active Provider

Set the active provider explicitly:
```properties
active.provider=deepseek
```

Or let auto-detection pick the first configured provider.

## Model Picker

The `/model` command shows available models. Populate the picker:
```properties
openai.models=gpt-4o,gpt-4o-mini,gpt-4.1,o3-mini
```

## Model Override (CLI Flag)

```bash
core-ai --model gpt-4o-mini
```

Overrides the configured model for this session only.

## Sub-Agent Model Override

```properties
agent.sub.code-reviewer.model=gpt-4o
agent.sub.code-reviewer.provider=openai
```

## Shared Fallback Properties

Properties without a provider prefix act as fallbacks for all providers:
```properties
llm.model=gpt-4o
llm.temperature=0.7
llm.timeout.seconds=300
```

Provider-specific properties override these when set.

## Interactive Configuration

When no config file exists, core-ai launches an interactive wizard that prompts for:
- API base URL (LiteLLM)
- API key
- Model name
- Username
- Coding mode preference

The wizard creates `~/.core-ai/agent.properties` with LiteLLM as default provider.
