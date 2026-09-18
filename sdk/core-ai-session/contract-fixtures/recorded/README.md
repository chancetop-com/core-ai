# Recorded CLI envelopes

The `*.json` files here are not hand-written. Each one is a single exchange between the Python SDK
and a **real** `core-ai-cli` talking to a real hub, captured through `CliSession(record=…)` and then
curated:

```bash
# 1. capture the raw exchanges (read-only: it lists, describes and makes one harmless call)
python sdk/core-ai-session/tests/record_live_cli.py --record /tmp/exchanges
# 2. select, trim and redact them into this directory
python sdk/core-ai-session/tests/curate_recordings.py \
    --from /tmp/exchanges --out sdk/core-ai-session/contract-fixtures/recorded
# 3. replay them to the SDK from tests
python sdk/core-ai-session/tests/replay_cli.py mcp describe MongoDB/list-databases
```

A fixture keeps the CLI's own bytes — field names, nesting, types, casing, exit codes — with the
*values* reduced by `curate_recordings.py`, which writes down exactly what it replaced in
`manifest.json`: prose and output keys such as `description` and `text` become
`[redacted in the recorded fixture]`; private names are renamed (`order-service` → `restaurant-api`);
an internal route keeps only its last segment (`path: "/order/update"` → `/[route]/update`); absolute
paths, emails, private hosts, UUIDs and 24+ hex ids are pseudonymised; long lists are trimmed to a few
entries. Two things are kept on purpose: `input_schema` stays structured (its field names and
`required` list are the point), and `mcp-call-business-failure`'s message, which is a real error
message quoting only our own bogus argument. An internal type name that arrives as a *value* — a
back-office DTO in `request_type`/`response_type` — is redacted like any other prose, while the keys
themselves stay. Counts, states and timestamps are the CLI's own; nothing there names a person or a
private host.

Curation ends by auditing its own output: `leaks()` refuses to write a fixture that still shows an
email, an absolute path, a private host, a raw UUID or 24+ hex id, an internal `BO…Request` class
name, or any name `RENAMES` should have replaced. A capture taken on another hub can carry a shape the
script has not seen, and failing loudly beats publishing it.

`test_recorded.py` is the consumer: it points the SDK at `replay_cli.py`, which re-serves these
envelopes, so every assertion is about the real wire rather than about our reading of it.

What these pin that a hand-written fixture cannot:

* `mcp servers` omits `description`/`category` for a server that has none, instead of sending `null`;
* a tool's `input_schema` arrives as **JSON text**, not as an object, and a describe answers
  `server_state` alongside the schema;
* an `mcp call` answers with `content[]` **and** a top-level `text`, plus `duration_ms` and
  `server_state`; a *business* failure exits `1` **with** that payload, and the message the SDK
  raises is the one in `text`;
* a not-found is exit `5` with `{"error":{"code","status","message"}}`, and the CLI logs a WARN line
  to stderr as well;
* a usage error is exit `2` with **nothing** on stdout and the message on stderr;
* `api-tool` operations carry `qualified_name`, `tool_name`, `ref_id`, `method`, `path`,
  `deprecated` and `score`, and an app carries `base_url`, `version`, `service_count`,
  `operation_count`;
* `agent search` returns agents and llm_calls in one list, told apart by `type`, with `skill_names`,
  `sub_agent_names`, `has_sandbox`, `system_default` and `published_at` — fields the model-less fake
  CLI does not send.

To record one more surface, add a `Case` to `CASES` in `curate_recordings.py`, re-run the capture and
curation, and keep the renames in `manifest.json` accurate: it is the provenance record for every
byte in here.

One rule keeps the set self-consistent: a tool or operation that a describe case covers must stay in
the search fixture that puts it into the catalog — the SDK addresses a describe by its catalogued path,
so a describe whose subject is not in the catalog is unreachable. `test_recorded.py` fails if that
happens (its describe assertions would find no recording), and `keep_named` accepts a full
`qualified_name` precisely so a fixture can name the one entry it wants among the `cancel`-looking
twins.

The `mcp servers` / `api-tool apps` recordings keep the hub's own counts, while the search recordings
keep only the samples the tests exercise — so a replayed catalog is deliberately *smaller* than the
counts it advertises. `CliSession` notices exactly that and reports it (`session.catalog_gaps`,
one warning), which is the behaviour a real flapping index produces; `test_recorded.py` pins the
message. Do not "fix" the counts by hand: they are the real wire bytes, and the shortfall is the
point.
