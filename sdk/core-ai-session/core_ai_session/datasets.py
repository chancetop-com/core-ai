"""``s.dataset`` — the datasets this session's agent mounts, read and written the same way anywhere.

One dataset is one *binding*: a dataset id plus the permission this session holds on it, both decided
by the agent definition (``AgentDatasetConfig``) and both visible in the catalog. A script addresses a
binding by name when that name is unambiguous, or by id always::

    from core_ai_session import session

    s = session()                              # sandbox: the session hub; locally: core-ai-cli
    menu = s.dataset["menu-state"]             # a SESSION dataset: one state document per session
    state = menu.state()                       # None when this session never wrote one
    menu.patch({"menuPublished": True})        # JSON-merge patch, one saved state per session

    log = s.dataset["review-log"]              # a GENERAL dataset: one record per call
    log.insert({"review_id": "r-1", "replied_at": "2026-09-20T10:00:00Z"})
    log.records(filter={"review_id": "r-1"}, limit=10)

What the SDK adds over calling the tools yourself is *not* a second permission model: the table in
``dataset_ops.py`` reaches the very same tools the agent calls (through the hub inside a sandbox,
through ``core-ai-cli dataset`` locally), so a refusal reads the same in both places, and the checks
below only repeat the server's own before spending a round trip.

The three shapes a dataset can have are strict, not best-effort:

* ``SESSION`` datasets hold state — ``state()``/``set()``/``patch()``, and nothing else;
* ``GENERAL`` datasets hold records — ``records()``/``insert()``/``update()``/``delete()`` only;
* a dataset the session may only read refuses a write here, in the server's own words.

Ambiguity is refused rather than resolved: two bindings may share a name, and a write that lands in
the wrong dataset is worse than an error, so ``s.dataset["review-log"]`` lists the candidate ids and
asks for one. Which session a *local* process acts on is never guessed either — pass
``session(session_id="...")`` (or ``--session`` on the CLI), or set ``CORE_AI_SESSION_ID``.
"""

from __future__ import annotations

import difflib
import logging
from typing import TYPE_CHECKING, Any, Optional, Union

from .dataset_ops import (
    DELETE,
    GENERAL_TYPE,
    MISSING_SESSION_ID,
    WRITE,
    DatasetOp,
    ambiguous_message,
    dataset_op,
    json_object,
    json_text,
    name_list,
    refused,
    wrong_dataset_type,
)
from .errors import CoreAiSessionError, ToolNotFoundError
from .models import DatasetInfo

if TYPE_CHECKING:  # the session owns the transports; datasets only decides what to ask them for
    from .tree import _SessionBase

LOGGER = logging.getLogger("core_ai_session")


class _DatasetOps:
    """Binding lookup, the pre-flight checks and payload unwrapping — shared by every transport.

    Both namespaces (sync and async) resolve, refuse and unwrap identically; the only thing a
    transport changes is *how* one operation is carried out.
    """

    def __init__(self, session: "_SessionBase") -> None:
        self._session = session

    # ---------- bindings ----------

    def bindings(self) -> list[DatasetInfo]:
        """What this session may reach, in binding order (the server's order, kept as it is)."""
        return self._session._require_catalog().datasets

    def _require_local_anchor(self) -> None:
        """Refuse dataset work from a local run that named no session, with the reason, not the symptom.

        A local transport answers ``""`` here when it has no ``--session``/``session_id`` to act on, and
        then every name looks unbound: the case is worth its own sentence, because the fix is an id,
        not a different dataset. Inside a sandbox the token already names the session (``None``).
        """
        if self._session._local_anchor() == "":
            raise CoreAiSessionError(MISSING_SESSION_ID)

    def resolve(self, ref: str) -> DatasetInfo:
        """One binding by id (always) or by name (when exactly one binding has it).

        A name two bindings share is an error listing the ids, not a coin flip (see the module
        docstring); a ref that is not bound at all is a `ToolNotFoundError`, like an unknown tool.
        """
        self._require_local_anchor()
        wanted = str(ref or "").strip()
        bindings = self.bindings()
        for info in bindings:
            if info.dataset_id == wanted:
                return info
        named = [info for info in bindings if info.name and info.name == wanted]
        if len(named) == 1:
            return named[0]
        if len(named) > 1:
            raise CoreAiSessionError(ambiguous_message(wanted, [info.dataset_id for info in named]))
        close = difflib.get_close_matches(wanted, [info.name or info.dataset_id for info in bindings], n=3, cutoff=0.3)
        hint = f"; did you mean: {', '.join(close)}" if close else ""
        raise ToolNotFoundError(f"{wanted!r} is not one of this session's datasets (bound: {self.names()}){hint}")

    def names(self) -> str:
        return ", ".join(info.name or info.dataset_id for info in self.bindings()) or "none"

    # ---------- checks and unwrapping ----------

    def _check(self, op: DatasetOp, info: DatasetInfo) -> None:
        """Refuse what the server would refuse, before the round trip and in its own words.

        The sentences are the server's own (`dataset_ops.refused` / `wrong_dataset_type`), naming the
        dataset id the call carries — so the same refusal reads identically whether the SDK or the
        server caught it, and the order is the server's: permission before dataset type.
        """
        label = info.dataset_id
        if op.permission == WRITE and not info.writable:
            raise CoreAiSessionError(refused(WRITE, label))
        if op.permission == DELETE and not info.deletable:
            raise CoreAiSessionError(refused(DELETE, label))
        actual = (info.type or GENERAL_TYPE).upper()
        if op.family != actual:
            raise CoreAiSessionError(wrong_dataset_type(actual, label))

    def _decode(self, op: DatasetOp, info: DatasetInfo, payload: dict[str, Any]) -> Any:
        """The part of a payload the caller asked for: the state, the records, or the whole answer."""
        if op.unwrap == "state":
            return payload.get("state")
        if op.unwrap == "records":
            warning = payload.get("warning")
            if warning:
                LOGGER.warning("core_ai_session: %s (%s): %s", info.name or info.dataset_id, op.name, warning)
            records = payload.get("records")
            return records if isinstance(records, list) else []
        return payload


class _DatasetNode:
    """One resolved binding: identity plus the arguments each operation needs.

    The arguments are normalized here (a filter may be a dict or its JSON text, ``fields`` a list or
    ``"a,b"``, data an object or its JSON text) so both transports hand the tool the one spelling it
    declares, and both refuse the same wrong types with the same message.
    """

    def __init__(self, namespace: _DatasetOps, info: DatasetInfo) -> None:
        self._namespace = namespace
        self._info = info

    @property
    def info(self) -> DatasetInfo:
        """The binding as the catalog describes it."""
        return self._info

    @property
    def dataset_id(self) -> str:
        return self._info.dataset_id

    @property
    def name(self) -> str:
        return self._info.name

    @property
    def type(self) -> str:
        return self._info.type

    @property
    def permission(self) -> str:
        return self._info.permission

    @property
    def description(self) -> str:
        return self._info.description

    @property
    def schema(self) -> list[Any]:
        """The declared fields: what the record tools validate and what an LLM would be told."""
        return list(self._info.schema)

    def op(self, name: str, **kwargs: Any) -> dict[str, Any]:
        """Run one operation by name and hand back the payload as it arrived.

        The methods below cover the usual calls and unwrap the answer (``state()`` gives the state,
        not the envelope). Use this one when you want the envelope itself — ``total`` to page through
        records, or ``updated_fields`` to see what a patch touched::

            answer = s.dataset["review-log"].op("records.query", filter={"review_id": "r-1"})
            answer["total"], answer["records"]

        Operation names are the ones in ``dataset_ops.py`` (``state.get``, ``state.set``,
        ``state.patch``, ``records.query``, ``records.insert``, ``records.update``,
        ``records.delete``). Arguments travel in the tools' own spelling (``filter`` as text, ``data``
        as an object, ``from``/``to`` as ISO strings); nothing is validated here beyond the operation
        name, because the tool's schema — and behind it the server — is the authority on what an
        operation accepts.
        """
        return self._call(dataset_op(name), _raw_arguments(kwargs))

    def _call(self, op: DatasetOp, arguments: dict[str, Any]) -> dict[str, Any]:
        """One operation, carried out by this node's transport, payload exactly as it arrived."""
        raise NotImplementedError

    def _run(self, op: DatasetOp, arguments: dict[str, Any]) -> Any:
        raise NotImplementedError

    def __repr__(self) -> str:
        return f"<dataset {self._info.name or self._info.dataset_id} {self._info.type} {self._info.permission}>"


class DatasetNode(_DatasetNode):
    """The sync surface: each operation runs on the calling thread (one hub call, or one subprocess)."""

    def state(self, *, fields: Optional[Union[str, list[str]]] = None) -> Optional[dict[str, Any]]:
        """This session's state document, or ``None`` when it never wrote one.

        ``fields`` projects the top-level fields; a field that is not in the state is left out, so
        "never set" stays distinguishable from "set to null".
        """
        return self._run(dataset_op("state.get"), _state_arguments(fields))

    def set(self, data: Any) -> dict[str, Any]:
        """Replace this session's state with ``data`` (one document per session, ≤256 KB)."""
        return self._run(dataset_op("state.set"), _write_arguments(data))

    def patch(self, data: Any) -> dict[str, Any]:
        """Merge ``data`` into this session's state: top-level keys are replaced, the rest untouched.

        The answer carries ``updated_fields``: the top-level keys this call actually wrote.
        """
        return self._run(dataset_op("state.patch"), _write_arguments(data))

    def records(self, filter: Any = None, *, fields: Optional[Union[str, list[str]]] = None,
                from_: Optional[str] = None, to: Optional[str] = None, limit: Optional[int] = None,
                offset: Optional[int] = None) -> list[dict[str, Any]]:
        """Records matching ``filter``, newest first, each ``{id, run_id, agent_id, run_started_at, data}``.

        ``filter`` is a dict (or its JSON text) matched against the record data; ``from_``/``to`` are
        ISO timestamps bounding ``run_started_at``; ``limit`` defaults to 100 (max 1000), and a filter
        only scans the most recent 10 000 records — past that the answer is truncated and the warning
        is logged. ``fields`` projects the record data, not the envelope.
        """
        return self._run(dataset_op("records.query"), _query_arguments(filter, fields, from_, to, limit, offset))

    def insert(self, data: Any) -> dict[str, Any]:
        """Append one record built from ``data``; the answer lists ``inserted_fields``."""
        return self._run(dataset_op("records.insert"), {"data": json_object(data, "data")})

    def update(self, record_id: str, data: Any) -> dict[str, Any]:
        """Merge ``data`` into the record ``record_id`` (a subset of fields is a data-loss trap)."""
        return self._run(dataset_op("records.update"), _update_arguments(record_id, data))

    def delete(self, record_id: str) -> dict[str, Any]:
        """Delete the record ``record_id``; needs full permission on the binding."""
        return self._run(dataset_op("records.delete"), {"record_id": _required(record_id, "record_id")})

    def _call(self, op: DatasetOp, arguments: dict[str, Any]) -> dict[str, Any]:
        self._namespace._check(op, self._info)
        return self._namespace._session._dataset_call(op, self._info.dataset_id, arguments)

    def _run(self, op: DatasetOp, arguments: dict[str, Any]) -> Any:
        return self._namespace._decode(op, self._info, self._call(op, arguments))


class AsyncDatasetNode(_DatasetNode):
    """The awaited surface: identical operations, each waiting in a worker thread or on the loop."""

    async def op(self, name: str, **kwargs: Any) -> dict[str, Any]:  # type: ignore[override]
        return await self._call(dataset_op(name), _raw_arguments(kwargs))

    async def state(self, *, fields: Optional[Union[str, list[str]]] = None) -> Optional[dict[str, Any]]:
        return await self._run(dataset_op("state.get"), _state_arguments(fields))

    async def set(self, data: Any) -> dict[str, Any]:
        return await self._run(dataset_op("state.set"), _write_arguments(data))

    async def patch(self, data: Any) -> dict[str, Any]:
        return await self._run(dataset_op("state.patch"), _write_arguments(data))

    async def records(self, filter: Any = None, *, fields: Optional[Union[str, list[str]]] = None,
                      from_: Optional[str] = None, to: Optional[str] = None, limit: Optional[int] = None,
                      offset: Optional[int] = None) -> list[dict[str, Any]]:
        return await self._run(dataset_op("records.query"), _query_arguments(filter, fields, from_, to, limit, offset))

    async def insert(self, data: Any) -> dict[str, Any]:
        return await self._run(dataset_op("records.insert"), {"data": json_object(data, "data")})

    async def update(self, record_id: str, data: Any) -> dict[str, Any]:
        return await self._run(dataset_op("records.update"), _update_arguments(record_id, data))

    async def delete(self, record_id: str) -> dict[str, Any]:
        return await self._run(dataset_op("records.delete"), {"record_id": _required(record_id, "record_id")})

    async def _call(self, op: DatasetOp, arguments: dict[str, Any]) -> dict[str, Any]:  # type: ignore[override]
        self._namespace._check(op, self._info)
        return await self._namespace._session._dataset_call(op, self._info.dataset_id, arguments)

    async def _run(self, op: DatasetOp, arguments: dict[str, Any]) -> Any:  # type: ignore[override]
        return self._namespace._decode(op, self._info, await self._call(op, arguments))


class DatasetNamespace(_DatasetOps):
    """``s.dataset``: list the bindings, then address one by name or id."""

    def list(self) -> list[DatasetInfo]:
        """What this session may reach: id, name, type, permission, description, schema per dataset."""
        self._require_local_anchor()
        return list(self.bindings())

    def __getitem__(self, ref: str) -> DatasetNode:
        return DatasetNode(self, self.resolve(ref))

    def __repr__(self) -> str:
        state = "unloaded" if self._session._catalog is None else self.names()
        return f"<datasets {state}>"


class AsyncDatasetNamespace(DatasetNamespace):
    """``s.dataset`` on an `AsyncSession`: the same lookups, awaited operations."""

    async def list(self) -> list[DatasetInfo]:  # type: ignore[override]
        self._require_local_anchor()
        return list(self.bindings())

    def __getitem__(self, ref: str) -> AsyncDatasetNode:
        return AsyncDatasetNode(self, self.resolve(ref))


def _required(value: Any, argument: str) -> str:
    text = str(value or "").strip()
    if not text:
        raise TypeError(f"{argument} is required")
    return text


def _state_arguments(fields: Optional[Union[str, list[str]]]) -> dict[str, Any]:
    return {"fields": name_list(fields, "fields")} if fields is not None else {}


def _write_arguments(data: Any) -> dict[str, Any]:
    if data is None:
        raise TypeError("data is required: pass the object to save")
    return {"data": json_object(data, "data")}


def _update_arguments(record_id: str, data: Any) -> dict[str, Any]:
    if data is None:
        raise TypeError("data is required: pass the fields to merge")
    return {"record_id": _required(record_id, "record_id"), "data": json_object(data, "data")}


def _query_arguments(filter: Any, fields: Optional[Union[str, list[str]]], from_: Optional[str],
                     to: Optional[str], limit: Optional[int], offset: Optional[int]) -> dict[str, Any]:
    arguments: dict[str, Any] = {}
    if filter is not None:
        arguments["filter"] = json_text(filter, "filter")
    if fields is not None:
        arguments["fields"] = name_list(fields, "fields")
    if from_ is not None:
        arguments["from"] = str(from_)
    if to is not None:
        arguments["to"] = str(to)
    if limit is not None:
        arguments["limit"] = int(limit)
    if offset is not None:
        arguments["offset"] = int(offset)
    return arguments


def _raw_arguments(kwargs: dict[str, Any]) -> dict[str, Any]:
    """``op()``'s own spelling: ``from_`` is Python's problem, ``from`` is the tool's."""
    arguments: dict[str, Any] = {}
    for key, value in kwargs.items():
        if value is None:
            continue
        arguments["from" if key == "from_" else key] = value
    return arguments
