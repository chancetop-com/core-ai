---
name: ocg
description: Read and reply to IM messages (QQ/Telegram/WeChat/DingTalk/Discord) through a local OpenClaw Channel Gateway (OCG) daemon. Use when the user asks to check incoming messages, answer an IM conversation, or send an IM message to a previously seen contact/group.
---

# OCG — IM mailbox for coding agents (v1.3.1)

OCG runs as a local daemon ("gateway") connected to the user's IM channels
(QQ Bot, Telegram, WeChat, DingTalk, Discord). In **mailbox mode** incoming
messages are queued in the daemon and you pull them with `ocg inbox wait`,
then answer with `ocg reply`. You act like a mail client: fetch, process,
reply. The user's messaging platforms handle delivery.

## Prerequisites

```bash
ocg doctor          # first stop when anything fails
ocg daemon status   # is the daemon up?
ocg daemon start    # start it if not (safe: leaves an already-running daemon alone)
```

The daemon must be running with `agentType: "mailbox"` (globally or for at
least one channel). If `doctor` reports the mailbox is disabled, tell the
user — do not edit their `ocg.json` yourself unless asked.

## Core loop

```bash
# 1. Wait for the next message (blocks up to --timeout seconds; exit 0 with an
#    empty list on timeout — "no messages" is NOT an error)
ocg inbox wait --timeout 60 --json

# 2. Read the JSON, do the work the message asks for.

# 3. Reply to that message id (--text, --text-file, or "-" to read stdin)
ocg reply m_xxxxxxxx --text "your answer" --json
echo "long answer ..." | ocg reply m_xxxxxxxx - --json              # stdin
ocg reply m_xxxxxxxx --text "see chart" --media https://…/chart.png --json
ocg reply m_xxxxxxxx --text "sorry, that failed" --error --json     # marks it as an error reply
```

Hints:

- Reply **as soon as you can**: `replyExpiresAt` in the message JSON is the
  deadline for the fast path (the plugin's passive reply window). After it,
  `reply` still works but is delivered as a proactive send — which some
  platforms restrict (see Platform limits).
- One message = one reply. A second `reply` for the same id returns
  `ALREADY_REPLIED`; split long answers across messages with `ocg send`.
- Keep a lease shorter than `mailboxLeaseMs` (default 5 min): if you crash
  mid-processing, the message becomes visible again after the lease expires.

## Message JSON (one entry of `messages[]`)

| Field | Meaning |
| --- | --- |
| `id` | Message id — pass it to `reply` / `inbox ack` |
| `channel` | Platform id, e.g. `qqbot`, `telegram` |
| `target` | The peer address for proactive sends (may be `null`) |
| `sessionKey` | Stable conversation key — use it as YOUR memory key for this user/group |
| `chatType` | `direct` / `group` / `channel` / `unknown` |
| `text` | The message text (untruncated) |
| `media[]` | `{ path, url, mime }` — local `path` may still be downloading (`null`); re-check with `ocg inbox show <id>` |
| `sender` | `{ id, name }` |
| `replyable` / `replyExpiresAt` | Is the passive-reply window still open, and until when |
| `groupSubject`, `platformMessageId`, `replyToId`, `wasMentioned` | Context fields |

Process by `sessionKey` to keep per-conversation memory across days.

## Parallel consumers

Several agents (or sub-agents) may pull from the same mailbox. Give each a
name so leases are attributable; each message is delivered to exactly one
consumer:

```bash
OCG_CONSUMER=worker-1 ocg inbox wait --timeout 60 --json
```

Useful extras:

```bash
ocg inbox list --state pending,leased --json   # inspect the queue (default)
ocg inbox list --state all --json
ocg inbox show m_xxxxxxxx --json               # full detail incl. media paths
ocg inbox ack m_xxxxxxxx                       # handled, no reply needed
ocg inbox ack m_xxxxxxxx --force               # break someone else's lease (human intervention)
```

## Sending proactively (no inbound message)

```bash
ocg targets list --json        # recently seen peers: use their target values
ocg send --channel qqbot --to qqbot:c2c:OPENID --text "build finished" --json
```

Requires `sendSecret`/`callbackSecret` in the OCG config; without it the
command exits 3 with `DISABLED`.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Success (for `inbox wait`: also "no messages before timeout") |
| 1 | Delivery / platform failure (`PLATFORM_SEND_FAILED`, `REPLY_EXPIRED`, `ALREADY_REPLIED`, lease conflict) |
| 2 | Bad arguments / unknown id |
| 3 | Daemon not running, auth failed, or capability disabled (`DISABLED`, `MAILBOX_DISABLED`) |

Every `--json` command returns one object: `{ok:true, …}` or
`{ok:false, code, message, hint?}`. On exit 3, run `ocg doctor`.

## Platform limits (why reply-first matters)

- **QQ Bot**: proactive messages outside the session window are rejected;
  the passive reply window is the reliable path.
- **Telegram**: a bot cannot START a chat — the user must have messaged first.
- If a platform error comes back as `PLATFORM_SEND_FAILED`, do NOT retry in a
  loop; report the platform message to the user.

## Don't

- Don't `ocg daemon stop` the user's daemon unless the user asked.
- Don't retry platform errors automatically.
- Don't paste message contents into public logs, commits or shared files —
  messages are private user data.
