"""Exception hierarchy of the core-ai session SDK.

Every failure raises: a script must never see a half-filled ``ToolResult`` and continue as if
the call succeeded. `ToolError`, `NotBoundError` and `ToolNotFoundError` map one-to-one onto
the ``core-ai-sandbox`` CLI exit codes (1, 3, 5) so bash and Python skills fail the same way.
"""

from __future__ import annotations

from typing import Optional


class CoreAiSessionError(Exception):
    """Base class of every error raised by this package."""


class NotBoundError(CoreAiSessionError):
    """The sandbox is not bound to a session yet (runtime 503 ``not_bound``)."""


class ToolNotFoundError(CoreAiSessionError):
    """The requested tool is not in this session's catalog."""


class ToolError(CoreAiSessionError):
    """The tool ran and failed, or the hub rejected the call."""

    def __init__(
        self,
        message: str,
        *,
        tool: Optional[str] = None,
        kind: Optional[str] = None,
        status_code: Optional[int] = None,
        call_id: Optional[str] = None,
        task_id: Optional[str] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.tool = tool
        self.kind = kind
        # For API tools this is the upstream HTTP status; for hub-level failures the hub status.
        self.status_code = status_code
        self.call_id = call_id
        self.task_id = task_id
