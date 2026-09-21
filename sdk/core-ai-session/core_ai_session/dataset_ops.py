"""The dataset operation table: one row per op, shared by both transports.

``s.dataset`` reads and writes datasets through the seven dataset **tools** (a sandbox hub call and
a local ``core-ai-cli dataset`` subprocess end up invoking the same tool, so permission checks, error
text and the payload shape are identical by construction). Each row therefore carries everything the
SDK needs to reach that one operation:

* ``name`` — the operation as the design names it (``state.set``, ``records.query``, …), which is also
  what ``DatasetNode.op()`` and the test fakes address;
* ``tool`` and ``flags`` — how the operation reaches the *tool* (the sandbox's route);
* ``cli`` words — how it reaches *``core-ai-cli dataset``* (the local route), where the dataset is a
  positional argument, a write carries its JSON on stdin (``--data -``) and ``--fields`` names the
  projected fields. Both spellings are the ones `core-ai-sandbox dataset` shares, so one command line
  works in either place.

``data`` and ``record_id`` are not listed per row: the tool takes record data as an **object** and the
CLI takes it as **JSON text on stdin**, so each transport renders those two itself from the one
``data``/``record_id`` pair (``op.arguments()`` vs ``op.cli_argv()``/``op.stdin()``).
"""

from __future__ import annotations

import json
from typing import Any, NamedTuple, Optional, Sequence

from .errors import CoreAiSessionError

# Which dataset type an operation belongs to, and so on. The server refuses a mismatch anyway; these
# let the SDK refuse one *before* a round trip, in the server's own sentence.
SESSION_TYPE = "SESSION"
GENERAL_TYPE = "GENERAL"

# The permission each operation needs: reads need the binding, writes need WRITE or FULL, delete needs
# FULL. `s.dataset` never widens what the session agent mounts — it only reproduces the same refusals.
READ = "read"
WRITE = "write"
DELETE = "delete"

# The environment a local session anchors itself to when it was not opened with an id (`session_id=`).
SESSION_ID_ENV = "CORE_AI_SESSION_ID"
MISSING_SESSION_ID = (
    f"s.dataset needs a session id locally: pass session(session_id='<id>') (or --session <id>), "
    f"or set {SESSION_ID_ENV}. Inside a sandbox the runtime's hub token already names the session."
)


def refused(permission: str, dataset_id: str) -> str:
    """The server's refusal sentence for a permission this session does not hold (verbatim).

    The SDK answers with the same words the tool would have, so a script matches one sentence no
    matter which side refused first.
    """
    prefix = "" if permission == READ else f"{permission} "
    return f"{prefix}access denied to dataset: {dataset_id}"


def wrong_dataset_type(actual_type: str, dataset_id: str) -> str:
    """The server's refusal sentence for an operation used on the other kind of dataset (verbatim).

    Both sentences name the tools that *would* work: state operations are refused by the record
    tools, and record operations by the state tools.
    """
    if actual_type.upper() == SESSION_TYPE:
        return ("session dataset is not accessible via dataset record tools, "
                f"use get_session_state/set_session_state instead: {dataset_id}")
    return f"not a session dataset, use dataset record tools instead: {dataset_id}"


def ambiguous_message(dataset_ref: str, candidates: Sequence[str]) -> str:
    """The server's sentence for a name bound more than once: fail closed, name the candidates."""
    return f"dataset name is ambiguous, pass the dataset id: {dataset_ref} (candidates: {', '.join(candidates)})"


class DatasetOp(NamedTuple):
    """One dataset operation. See the module docstring for what each field is for."""

    name: str
    tool: str
    family: str
    permission: str
    cli: tuple[str, ...]
    flags: tuple[tuple[str, str], ...] = ()  # (argument, --flag), shared by both transports
    data: bool = False  # the tool's `data` object, the CLI's stdin text
    record_id: bool = False
    unwrap: str = "payload"  # state | records | payload

    def arguments(self, dataset_id: str, arguments: dict[str, Any]) -> dict[str, Any]:
        """The tool arguments for this operation, in the tools' own spelling."""
        rendered: dict[str, Any] = {"dataset_id": dataset_id}
        for name, _ in self.flags:
            if arguments.get(name) is not None:
                rendered[name] = arguments[name]
        if self.data:
            rendered["data"] = arguments["data"]
        if self.record_id:
            rendered["record_id"] = arguments["record_id"]
        return rendered

    def cli_argv(self, dataset_id: str, arguments: dict[str, Any]) -> list[str]:
        """The ``core-ai-cli dataset …`` argv for this operation, minus ``--session``/``--timeout``."""
        argv = ["dataset", *self.cli, dataset_id]
        for name, flag in self.flags:
            if arguments.get(name) is not None:
                argv += [flag, str(arguments[name])]
        if self.record_id:
            argv += ["--record-id", str(arguments["record_id"])]
        if self.data:
            # the payload travels on stdin: a state document or a record can be long, and quoting JSON
            # through argv is where "works on my machine" starts
            argv += ["--data", "-"]
        return argv

    def stdin(self, arguments: dict[str, Any]) -> Optional[str]:
        """What the CLI reads on stdin, or ``None``: record data travels as JSON text, never as argv."""
        if not self.data:
            return None
        data = arguments.get("data")
        return json.dumps(data, ensure_ascii=False) if data is not None else None


DATASET_OPS: tuple[DatasetOp, ...] = (
    DatasetOp(
        name="state.get", tool="get_session_state", family=SESSION_TYPE, permission=READ,
        cli=("state", "get"), flags=(("fields", "--fields"),), unwrap="state",
    ),
    DatasetOp(
        name="state.set", tool="set_session_state", family=SESSION_TYPE, permission=WRITE,
        cli=("state", "set"), data=True,
    ),
    DatasetOp(
        name="state.patch", tool="update_session_state", family=SESSION_TYPE, permission=WRITE,
        cli=("state", "patch"), data=True,
    ),
    DatasetOp(
        name="records.query", tool="query_dataset_records", family=GENERAL_TYPE, permission=READ,
        cli=("records", "query"),
        flags=(("filter", "--filter"), ("fields", "--fields"), ("from", "--from"), ("to", "--to"),
               ("limit", "--limit"), ("offset", "--offset")),
        unwrap="records",
    ),
    DatasetOp(
        name="records.insert", tool="insert_dataset_record", family=GENERAL_TYPE, permission=WRITE,
        cli=("records", "insert"), data=True,
    ),
    DatasetOp(
        name="records.update", tool="update_dataset_record", family=GENERAL_TYPE, permission=WRITE,
        cli=("records", "update"), data=True, record_id=True,
    ),
    DatasetOp(
        name="records.delete", tool="delete_dataset_record", family=GENERAL_TYPE, permission=DELETE,
        cli=("records", "delete"), record_id=True,
    ),
)

OP_INDEX = {op.name: op for op in DATASET_OPS}


def dataset_op(name: str) -> DatasetOp:
    """One row by operation name; an unknown name lists the ones that exist."""
    key = str(name or "").strip()
    found = OP_INDEX.get(key)
    if found is not None:
        return found
    raise CoreAiSessionError(
        f"unknown dataset operation {key!r}: known operations are {', '.join(op.name for op in DATASET_OPS)}"
    )


def ops_for(family: str) -> tuple[DatasetOp, ...]:
    return tuple(op for op in DATASET_OPS if op.family == family)


def json_text(value: Any, argument: str) -> str:
    """``filter``/``fields``-style arguments: accept the value or its JSON text, send text.

    The tools take ``filter`` as a JSON string, so a dict is dumped here rather than being refused —
    a script that has the filter in hand should not have to remember which spelling travels.
    """
    if isinstance(value, str):
        return value
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    raise TypeError(f"{argument} must be a JSON string or an object, not {type(value).__name__}")


def json_object(value: Any, argument: str) -> dict[str, Any]:
    """Record data: accept an object or its JSON text, hand the tool a real object."""
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except ValueError as error:
            raise ValueError(f"{argument} is not valid JSON: {error}") from error
        if not isinstance(parsed, dict):
            raise TypeError(f"{argument} must be a JSON object, not a {type(parsed).__name__}")
        return parsed
    raise TypeError(f"{argument} must be a JSON object or its JSON text, not {type(value).__name__}")


def name_list(value: Any, argument: str) -> str:
    """``fields``: accept ``"a,b"`` or ``["a", "b"]``, always send the comma-separated spelling."""
    if isinstance(value, str):
        return value
    if isinstance(value, (list, tuple)):
        names = [str(item).strip() for item in value]
        if any(not name for name in names):
            raise ValueError(f"{argument} must be a list of names, got an empty one")
        return ",".join(names)
    raise TypeError(f"{argument} must be a comma-separated string or a list of names, not {type(value).__name__}")
