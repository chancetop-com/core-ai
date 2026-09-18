"""core-ai session SDK: this session's capabilities, callable from a sandbox script or locally.

`session()` picks its transport from the environment: the sandbox hub inside a sandbox
(``CORE_AI_HUB`` is set), the local ``core-ai-cli`` everywhere else. `CliSession` is that local
transport, `FakeSession` the offline one for unit tests.
"""

from . import testing  # noqa: F401  (importable as `from core_ai_session import testing`)
from .cli import AsyncCliSession, CliSession
from .errors import CoreAiSessionError, NotBoundError, ToolError, ToolNotFoundError
from .models import Catalog, CatalogGroup, ContentPart, LlmUsage, SessionInfo, Task, ToolDetail, ToolResult, ToolSummary
from .session import AsyncSession, Session, async_session, sdk_version, session
from .testing import FakeSession

__all__ = [
    "session",
    "async_session",
    "Session",
    "AsyncSession",
    "CliSession",
    "AsyncCliSession",
    "FakeSession",
    "sdk_version",
    "Catalog",
    "CatalogGroup",
    "ContentPart",
    "LlmUsage",
    "SessionInfo",
    "Task",
    "ToolDetail",
    "ToolResult",
    "ToolSummary",
    "CoreAiSessionError",
    "NotBoundError",
    "ToolError",
    "ToolNotFoundError",
]
