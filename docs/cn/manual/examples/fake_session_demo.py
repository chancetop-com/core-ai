"""Offline SDK demo. Calls FakeSession only, never session() or an HTTP endpoint."""
import json
from core_ai_session import FakeSession

catalog = {
    "contract_version": "1.0",
    "tools": [{
        "name": "demo_echo", "kind": "mcp", "group": "demo",
        "path": "demo/echo", "ref_id": "mcp:demo:echo",
        "description": "离线回显", "exposure": "direct",
        "input_schema": {"type": "object", "properties": {
            "text": {"type": "string"}}, "required": ["text"]}
    }]
}
s = FakeSession(catalog=catalog)
hits = [t for t in s.catalog().tools if t.kind == "mcp" and "echo" in t.path]
detail = s.describe("demo/echo")
s.mcp["demo"]["echo"].returns('{"message":"离线调用成功"}')
result = s.mcp["demo"]["echo"](text="你好")
assert result.data == {"message": "离线调用成功"}
assert len(s.calls) == 1
assert s.calls[0].arguments == {"text": "你好"}
print(json.dumps({"backend": s.backend,
    "matched": len(hits), "path": detail.path,
    "result": result.data, "recorded_calls": len(s.calls)},
    ensure_ascii=False, indent=2))
