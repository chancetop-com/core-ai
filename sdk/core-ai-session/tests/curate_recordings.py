"""Turn a raw ``record_live_cli.py`` capture into the curated fixtures in ``sdk/core-ai-session/contract-fixtures/recorded/``.

    python sdk/core-ai-session/tests/record_live_cli.py --record /tmp/exchanges
    python sdk/core-ai-session/tests/curate_recordings.py \\
        --from /tmp/exchanges --out sdk/core-ai-session/contract-fixtures/recorded

A recording is the CLI's own bytes, but it was taken on somebody's machine against their tools, so a
fixture may not be published raw. Curation does three things, and writes down that it did them:

* **selects** the exchanges that make up the contract — one ``mcp servers``, one search per kept
  server, the describes, the read-only call, one real failure call, the error envelopes;
* **trims** the lists that would otherwise be a whole private catalog (all servers, all tools, all
  agents) down to ``--keep`` entries;
* **redacts**: tool descriptions and tool output become a placeholder, absolute paths/hosts/ids are
  pseudonymised, and a private name is mapped to a placeholder by `RENAMES` — the mapping itself is
  written into the manifest, so a reader can see exactly what was replaced.

Then it audits its own output and refuses to write a fixture that still shows private data (`leaks`),
because a capture taken on another hub can carry a shape this script has not seen.

The result is *shape-true and value-redacted*: field names, nesting, types, casing and exit codes
are the CLI's; the values are not the author's data.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Optional

CAPTURE_FILE = "capture.json"
PLACEHOLDER = "[redacted in the recorded fixture]"
BOOLEAN_FLAGS = {"json", "raw", "quiet", "detach", "insecure", "help", "version", "stale"}
SELECTOR_FLAGS = ("on-server", "on-app", "type")  # flags that decide *which* resources come back

# Private names -> the placeholder a fixture carries. Public product names (MongoDB, elasticsearch,
# kubernetes, …) are not in here on purpose: they identify a product, not a company.
RENAMES: dict[str, str] = {
    "order-service": "restaurant-api",
    "order_service": "restaurant_api",  # the same app as the hub spells it in `tool_name`
    "BOOrderWebService": "reviews",
    "project-playbook-writer": "seo-title-semantics",
}
# Keys whose *value* is prose, private addressing, or an internal class name — never part of the
# contract's shape. `request_type`/`response_type` are a back-office DTO's Java name: the *field* is
# the contract, its value never is.
REDACT_KEYS = {"description", "base_url", "text", "output", "display_name", "title", "error_detail",
               "request_type", "response_type"}
# Keys whose value is JSON *text* (the hub sends a schema as a string) and stays structured inside.
JSON_STRING_KEYS = {"input_schema"}
# Keys whose value is an internal HTTP route; only its last segment survives.
ROUTE_KEYS = {"path"}
UUID_RE = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
# A host is printable if it documents a public product; anything else becomes `[host]`.
PUBLIC_HOSTS = ("googleapis.com", "google.com", "github.com", "mongodb.com", "elastic.co",
                "kubernetes.io", "wix.com", "slack.com", "atlassian.com", "microsoft.com")
# The pipeline's own failure modes, checked against the bytes we are about to write: a rename that
# never applied, a key nobody redacted, a pseudonym that never ran. A capture taken on another hub can
# carry a shape this script has not seen, and publishing one is worse than failing here.
LEAK_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("an email address", re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")),
    ("an absolute path", re.compile(r"[A-Za-z]:\\|/(?:home|Users|var|tmp|opt|etc)/")),
    ("an internal class name", re.compile(r"\bBO[A-Z][A-Za-z]*(?:Request|Response)\b")),
    ("a raw uuid", UUID_RE),
    ("a raw 24+ hex id", re.compile(r"\b[0-9a-fA-F]{24,}\b")),
    ("a name RENAMES should have replaced", re.compile("|".join(re.escape(name) for name in RENAMES))),
)


@dataclass
class Case:
    """One fixture: which recorded exchange it comes from, and how it is reduced."""

    name: str
    command: tuple[str, ...]
    selector: dict[str, str] = field(default_factory=dict)
    subject: str = ""  # positionals[2]: the tool/app/run this exchange is about
    subject_prefix: str = ""  # …when only the app is known, e.g. every operation of one app
    exit: Optional[int] = None
    note: str = ""
    keep: tuple[str, ...] = ()  # identity fields (name/id/qualified_name) of the items to keep
    trim: Optional[int] = None  # list entries kept *in this fixture* (default: the global `--keep`)
    keep_keys: tuple[str, ...] = ()  # keys whose value stays verbatim: reviewed, and holds nothing private


CASES: list[Case] = [
    Case("mcp-servers", ("mcp", "servers"), note="every server the CLI lists, trimmed to the kept ones",
         keep=("MongoDB", "elasticsearch", "kubernetes")),
    Case("mcp-search-mongodb", ("mcp", "search"), {"on-server": "MongoDB"},
         keep=("aggregate", "list-databases"),
         note="a server's tools, with ref_id; `list-databases` stays so its describe is reachable"),
    Case("mcp-search-elasticsearch", ("mcp", "search"), {"on-server": "elasticsearch"},
         note="a server with a handful of tools"),
    Case("mcp-search-kubernetes", ("mcp", "search"), {"on-server": "kubernetes"},
         note="a server whose tools take arguments"),
    Case("mcp-describe-kubernetes-namespaces-list", ("mcp", "describe"), subject="kubernetes/namespaces_list",
         note="a real input_schema string"),
    Case("mcp-describe-mongodb-list-databases", ("mcp", "describe"), subject="MongoDB/list-databases",
         note="the smallest schema: required and additionalProperties"),
    Case("mcp-describe-unknown", ("mcp", "describe"), subject="no-such-server/no-such-tool", exit=5,
         note="the not-found envelope"),
    Case("mcp-call-kubernetes-namespaces-list", ("mcp", "call"), subject="kubernetes/namespaces_list", exit=0,
         note="a successful HubCallResponse (output redacted)"),
    Case("mcp-call-business-failure", ("mcp", "call"), subject="MongoDB/list-databases", exit=1, keep_keys=("text",),
         note="exit 1 *with* a payload: the message arrives in `text` (kept: it only quotes our own argument)"),
    Case("api-tool-apps", ("api-tool", "apps"), note="the app list, renamed", keep=("order-service",)),
    Case("api-tool-search", ("api-tool", "search"), {"on-app": "order-service"},
         keep=("order-service/BOOrderWebService/batchUpdateOrderItemQuantity",),
         note="one operation of one app, renamed; the one its describe covers"),
    Case("api-tool-describe", ("api-tool", "describe"), subject_prefix="order-service/", trim=1,
         note="an operation's input_schema string"),
    Case("agent-search-agents", ("agent", "search"), {"type": "agent"}, trim=2,
         keep=("Agent Builder", "Assistant"), note="two agents, as the hub lists them"),
    Case("agent-search-llm-calls", ("agent", "search"), {"type": "llm_call"}, trim=1,
         keep=("project-playbook-writer",), note="one llm_call, renamed"),
    Case("cli-usage-error", ("definitely-not-a-command",), exit=2, note="the usage envelope (exit 2)"),
    Case("agent-status-unknown", ("agent", "status"), note="polling a run that does not exist"),
]


def split_argv(argv: Iterable[str]) -> tuple[list[str], dict[str, str]]:
    """``["mcp", "search", "--on-server", "X"]`` -> ``(["mcp", "search"], {"on-server": "X"})``."""
    positionals: list[str] = []
    flags: dict[str, str] = {}
    tokens = list(argv)
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.startswith("--"):
            name = token[2:]
            if name in BOOLEAN_FLAGS:
                flags[name] = "true"
                index += 1
            else:
                flags[name] = tokens[index + 1] if index + 1 < len(tokens) else ""
                index += 2
        else:
            positionals.append(token)
            index += 1
    return positionals, flags


@dataclass
class Recording:
    file: Path
    argv: list[str]
    stdin: str
    exit: int
    stdout: str
    stderr: str

    @property
    def command(self) -> tuple[str, ...]:
        return tuple(split_argv(self.argv)[0][:2])

    @property
    def flags(self) -> dict[str, str]:
        return split_argv(self.argv)[1]

    @property
    def positionals(self) -> list[str]:
        return split_argv(self.argv)[0]

    def matches(self, case: Case) -> bool:
        if self.command != case.command:
            return False
        if case.exit is not None and self.exit != case.exit:
            return False
        for flag, wanted in case.selector.items():
            if self.flags.get(flag) != wanted:
                return False
        if case.subject:
            subject = self.positionals[2] if len(self.positionals) > 2 else ""
            if subject != case.subject:
                return False
        if case.subject_prefix:
            subject = self.positionals[2] if len(self.positionals) > 2 else ""
            if not subject.startswith(case.subject_prefix):
                return False
        return True


class Redactor:
    """Renames private tokens, pseudonymises ids and paths, and blanks the keys that are prose."""

    def __init__(self, renames: dict[str, str], redact_keys: set[str],
                 json_string_keys: set[str] | None = None) -> None:
        self.renames = dict(sorted(renames.items(), key=lambda item: -len(item[0])))
        self.redact_keys = redact_keys
        self.json_string_keys = json_string_keys or set()
        self._ids: dict[str, str] = {}
        self._uuids: dict[str, str] = {}

    def invented(self) -> set[str]:
        """The fake ids this run handed out — what `leaks()` must not mistake for the real thing."""
        return set(self._ids.values()) | set(self._uuids.values())

    def text(self, value: str) -> str:
        for real, placeholder in self.renames.items():
            value = value.replace(real, placeholder)
        value = re.sub(r"[A-Za-z]:\\[^\s\"']*", "[path]", value)
        value = re.sub(r"/(?:home|Users|var|tmp|opt|etc)/[^\s\"']*", "[path]", value)
        value = re.sub(r"[\w.+-]+@[\w-]+\.[\w.-]+", "[email]", value)
        value = re.sub(r"https?://[^\s\"']+", lambda match: self.url(match.group(0)), value)
        value = re.sub(UUID_RE, lambda match: self.fake_uuid(match.group(0)), value)
        return re.sub(r"\b[0-9a-fA-F]{24,}\b", lambda match: self.pseudonym(match.group(0)), value)

    @staticmethod
    def url(value: str) -> str:
        """Keep a public documentation host; a private one becomes a host we are allowed to print."""
        host = value.split("//", 1)[-1].split("/", 1)[0].lower()
        return value if _public(host) else "https://[host]/"

    def pseudonym(self, value: str) -> str:
        if value not in self._ids:
            self._ids[value] = f"{len(self._ids) + 1:024x}"
        return self._ids[value]

    def fake_uuid(self, value: str) -> str:
        if value not in self._uuids:
            self._uuids[value] = f"00000000-0000-4000-8000-{len(self._uuids) + 1:012d}"
        return self._uuids[value]

    def walk(self, value: Any, keep: set[str] | None = None) -> Any:
        keep = keep or set()
        if isinstance(value, dict):
            return {key: self.item(key, item, keep) for key, item in value.items()}
        if isinstance(value, list):
            return [self.walk(item, keep) for item in value]
        if isinstance(value, str):
            return self.text(value)
        return value

    def item(self, key: str, value: Any, keep: set[str]) -> Any:
        if key in self.redact_keys and key not in keep and isinstance(value, str):
            return PLACEHOLDER
        if key in ROUTE_KEYS and isinstance(value, str):
            return re.sub(r"^/.*/", "/[route]/", value)
        if key in self.json_string_keys and isinstance(value, str):
            # a schema travels as JSON *text*: keep it structured so its field names stay checkable
            try:
                return json.dumps(self.walk(json.loads(value), keep), ensure_ascii=False, separators=(",", ":"))
            except ValueError:
                return PLACEHOLDER
        return self.walk(value, keep)


def _public(host: str) -> bool:
    host = host.lower()
    return any(host == name or host.endswith("." + name) for name in PUBLIC_HOSTS)


def leaks(text: str, redactor: Redactor) -> list[str]:
    """What is still printable in a curated fixture, named — the last gate before anything is written."""
    invented = redactor.invented()
    found: list[str] = []
    for name, pattern in LEAK_PATTERNS:
        for match in pattern.finditer(text):
            value = match.group(0)
            # a pseudonym this script hands out is not a leak, whether or not this run made it
            if value in invented or value.startswith("0" * 16) or value.startswith("00000000-0000-4000-8000-"):
                continue
            found.append(f"{name} ({value[:50]})")
    for host in re.findall(r"https?://([^\s\"'/]+)", text):
        if host != "[host]" and not _public(host):
            found.append(f"a private host ({host[:50]})")
    return found


def trim(value: Any, keep: int) -> Any:
    """Cut every list down to `keep` entries (the lists in these payloads are all flat)."""
    if isinstance(value, dict):
        return {key: trim(item, keep) for key, item in value.items()}
    if isinstance(value, list):
        return [trim(item, keep) for item in value[:keep]]
    return value


def keep_named(value: Any, wanted: tuple[str, ...]) -> Any:
    """Drop the list items that are none of `wanted`: the server/app/tool/agent filter of a payload.

    An item matches on any of its identity fields — ``name`` (a server or agent), ``id`` or
    ``qualified_name`` (an operation, which has several ``cancel``-looking twins) — so a fixture can
    name exactly the entry it wants without depending on the server's sort order.
    """
    if not wanted:
        return value
    if isinstance(value, dict):
        return {key: keep_named(item, wanted) for key, item in value.items()}
    if isinstance(value, list):
        def keep(item: Any) -> bool:
            if not isinstance(item, dict):
                return True
            if not any(field in item for field in ("name", "id", "qualified_name")):
                return True
            return any(item.get(field) in wanted for field in ("name", "id", "qualified_name"))
        return [keep_named(item, wanted) for item in value if keep(item)]
    return value


def load(directory: Path) -> list[Recording]:
    recordings: list[Recording] = []
    for file in sorted(directory.glob("*.json")):
        if file.name == CAPTURE_FILE:
            continue
        data = json.loads(file.read_text(encoding="utf-8"))
        recordings.append(Recording(
            file=file,
            argv=[str(part) for part in data.get("argv") or []],
            stdin=str(data.get("stdin") or ""),
            exit=int(data.get("exit") or 0),
            stdout=str(data.get("stdout") or ""),
            stderr=str(data.get("stderr") or ""),
        ))
    return recordings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="source", required=True, metavar="DIR", help="a record_live_cli.py capture")
    parser.add_argument("--out", required=True, metavar="DIR", help="where the curated fixtures go")
    parser.add_argument("--keep", type=int, default=3, help="list entries kept per fixture (default 3)")
    options = parser.parse_args()

    source, out = Path(options.source), Path(options.out)
    recordings = load(source)
    if not recordings:
        raise SystemExit(f"no recordings in {source}")

    redactor = Redactor(RENAMES, REDACT_KEYS, JSON_STRING_KEYS)
    built: list[tuple[Case, dict[str, Any]]] = []
    missing: list[str] = []
    leaking: list[str] = []

    for case in CASES:
        found = next((item for item in recordings if item.matches(case)), None)
        if found is None:
            missing.append(case.name)
            continue
        if found.stdout.strip():
            try:
                payload: Any = json.loads(found.stdout)
            except ValueError:
                payload = {"raw_stdout": redactor.text(found.stdout[:2000])}
        else:
            payload = None  # a usage error answers on stderr with nothing on stdout
        if payload is not None:
            payload = keep_named(payload, case.keep)
            payload = trim(payload, case.trim or options.keep)
            payload = redactor.walk(payload, set(case.keep_keys))
        fixture = {
            "note": case.note,
            "argv": [redactor.text(part) for part in found.argv],
            "stdin": "{}" if found.stdin.strip() in ("{}", "") else redactor.text(found.stdin),
            "exit": found.exit,
            "stdout": payload,
            "stderr": redactor.text(found.stderr)[:400],
        }
        printable = leaks(json.dumps(fixture, ensure_ascii=False), redactor)
        if printable:
            leaking.append(f"  {case.name}.json: " + "; ".join(sorted(set(printable))))
            continue
        built.append((case, fixture))

    if leaking:
        raise SystemExit("refusing to write a fixture that still shows private data — teach "
                         "curate_recordings.py about it (RENAMES/REDACT_KEYS) or drop the case:\n"
                         + "\n".join(leaking))

    out.mkdir(parents=True, exist_ok=True)
    manifest_cases: list[dict[str, Any]] = []
    for case, fixture in built:
        (out / f"{case.name}.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
                                              encoding="utf-8")
        manifest_cases.append({"file": f"{case.name}.json", "argv": fixture["argv"], "exit": fixture["exit"],
                               "note": case.note, "keys_kept": list(case.keep_keys)})

    capture = json.loads((source / CAPTURE_FILE).read_text(encoding="utf-8")) if (source / CAPTURE_FILE).exists() else {}
    manifest = {
        "captured": capture,
        "curated_by": "curate_recordings.py",
        "redaction": {
            "placeholder": PLACEHOLDER,
            "renames": RENAMES,
            "keys_replaced": sorted(REDACT_KEYS),
            "json_string_keys": sorted(JSON_STRING_KEYS),
            "route_keys": sorted(ROUTE_KEYS),
            "also": "absolute paths, private URLs, emails, UUIDs and 24+ hex ids are replaced; list "
                    f"payloads are cut to {options.keep} entries unless a fixture asks for fewer; a "
                    "final audit refuses to write a fixture that still shows any of those, a private "
                    "host or a name from `renames`",
        },
        "cases": manifest_cases,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"{len(manifest_cases)} fixtures written to {out}")
    for name in missing:
        print(f"  missing from this capture: {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
