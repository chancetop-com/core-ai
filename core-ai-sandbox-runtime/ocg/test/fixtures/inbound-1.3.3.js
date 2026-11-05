/**
 * Shared inbound dispatch for both plugin entry points (OCG 1.3.0).
 *
 * `dispatchReplyWithBufferedBlockDispatcher` (telegram/discord/qqbot …) and
 * `dispatchReplyFromConfig` (openclaw-weixin …) historically duplicated the
 * whole "extract ctx → pick transport → forward → deliver" pipeline.  It now
 * lives here once, with `agentType` selecting one of three transports:
 *
 *   - `mailbox` → enqueue into the daemon mailbox (pulled by `ocg inbox wait`)
 *   - `acp`     → local ACP stdio subprocess
 *   - `http`    → OpenAI-compatible endpoint (sync SSE or async callback)
 *
 * The two entries had slightly different delivery semantics, so both are
 * preserved via explicit options instead of being silently unified:
 * `finalPolicy: "always"` (buffered-block entry) delivers a final payload even
 * after block streaming; `"unless-blocks"` (from-config entry) skips it.
 *
 * See docs/techdesign-agent-cli.md §5.2 / §5.3.
 */
import { resolveChannelAcpConfig, resolveChannelAgentType, resolveChannelAgentUrl, resolveChannelIdFromContext, loadConfig, resolveMailboxSettings } from "./config.js";
import { ensureMailbox, newMessageId } from "./mailbox.js";
import { deliverPayloadInChunks, resolveReplyChunkSize, rewriteLocalMediaPathsForDelivery } from "./reply-chunking.js";
import { buildAcpConfigFromEnv, getAcpAgent } from "./acp-agent.js";
// ── Message extraction (§5.3) ─────────────────────────────────────────────
function stringOrNull(value) {
    return typeof value === "string" && value !== "" ? value : null;
}
function firstString(...values) {
    for (const value of values) {
        if (typeof value === "string" && value !== "")
            return value;
    }
    return "";
}
function stringArray(value) {
    if (!Array.isArray(value))
        return null;
    return value.filter((item) => typeof item === "string" && item !== "");
}
function normalizeChatType(value) {
    const text = typeof value === "string" ? value.toLowerCase() : "";
    if (text === "direct" || text === "dm" || text === "private" || text === "c2c")
        return "direct";
    if (text === "group")
        return "group";
    if (text === "channel")
        return "channel";
    return "unknown";
}
export function extractInboundMedia(ctx) {
    const paths = stringArray(ctx.MediaPaths) ?? (typeof ctx.MediaPath === "string" && ctx.MediaPath !== "" ? [ctx.MediaPath] : []);
    const urls = stringArray(ctx.MediaUrls) ?? (typeof ctx.MediaUrl === "string" && ctx.MediaUrl !== "" ? [ctx.MediaUrl] : []);
    const types = stringArray(ctx.MediaTypes) ?? (typeof ctx.MediaType === "string" && ctx.MediaType !== "" ? [ctx.MediaType] : []);
    const count = Math.max(paths.length, urls.length);
    const media = [];
    for (let index = 0; index < count; index += 1) {
        media.push({
            path: paths[index] ?? null,
            url: urls[index] ?? null,
            mime: types[index] ?? null,
        });
    }
    return media;
}
/**
 * Normalize a plugin ctx into an `InboundMessage`. Missing fields become
 * `null` — never guesses (risks R1: the two entries build ctx slightly
 * differently).
 */
export function extractInboundMessage(ctx, resolved) {
    const sessionKey = firstString(ctx.SessionKey, ctx.sessionKey, ctx.routeSessionKey) || "default";
    const channelId = resolved?.channelId ??
        resolveChannelIdFromContext(ctx) ??
        (sessionKey.includes(":") ? sessionKey.split(":")[0] : undefined) ??
        "unknown";
    const accountId = resolved?.accountId ??
        stringOrNull(ctx.AccountId ?? ctx.accountId) ??
        "default";
    const from = firstString(ctx.From, ctx.from);
    const target = from && from !== "unknown" ? from : null;
    return {
        id: newMessageId(),
        channel: channelId,
        accountId,
        target,
        sessionKey,
        chatType: normalizeChatType(ctx.ChatType ?? ctx.chatType ?? ctx.ConversationType),
        sender: {
            id: stringOrNull(ctx.SenderId ?? ctx.senderId),
            name: stringOrNull(ctx.SenderName ?? ctx.senderName ?? ctx.Sender),
        },
        groupSubject: stringOrNull(ctx.GroupSubject ?? ctx.groupSubject),
        text: firstString(ctx.BodyForAgent, ctx.Body, ctx.CommandBody, ctx.RawBody),
        media: extractInboundMedia(ctx),
        platformMessageId: stringOrNull(ctx.MessageSid ?? ctx.MessageId),
        replyToId: stringOrNull(ctx.ReplyToId ?? ctx.replyToId),
        wasMentioned: typeof ctx.WasMentioned === "boolean" ? ctx.WasMentioned : null,
        receivedAt: Date.now(),
    };
}
let warnedMailboxAsyncConflict = false;
export async function dispatchInbound(params) {
    const { ctx, cfg } = params;
    const deliver = params.deliver;
    const beforeDeliver = params.beforeDeliver;
    const onModelSelected = params.onModelSelected;
    const finalPolicy = params.finalPolicy ?? "always";
    const disableBlockStreaming = params.replyOptions?.disableBlockStreaming === true;
    const body = firstString(ctx.BodyForAgent, ctx.Body, ctx.CommandBody, ctx.RawBody);
    const sessionKey = firstString(ctx.SessionKey, ctx.sessionKey) || "default";
    const from = String(ctx.From ?? "unknown");
    // Resolve channel id from explicit context fields first, then SessionKey/From.
    const channelId = resolveChannelIdFromContext(ctx) || sessionKey.split(":")[0] || undefined;
    const agentUser = channelId && !sessionKey.startsWith(`${channelId}:`)
        ? `${channelId}:${sessionKey}`
        : sessionKey;
    /** Rewrite local file paths in reply text so QQ Bot plugin can access them. */
    const maybeRewriteMedia = (text) => channelId === "qqbot" ? rewriteLocalMediaPathsForDelivery(text) : text;
    const agentType = resolveChannelAgentType(channelId, cfg);
    // Resolve agent API URL — per-channel override first, then global fallback
    const agentUrl = agentType === "http" ? resolveChannelAgentUrl(channelId, cfg) : agentType === "mailbox" ? "mailbox://local" : "acp://stdio";
    const liteGw = (cfg.liteGateway ?? {});
    const model = liteGw.model ||
        (cfg.agents &&
            typeof cfg.agents === "object" &&
            cfg.agents.defaults &&
            typeof cfg.agents.defaults === "object"
            ? cfg.agents.defaults.model
            : undefined) ||
        process.env.OCG_MODEL ||
        "gpt-4o";
    // Some configs store model as a string, others as an object { primary: "..." }
    const modelStr = typeof model === "string"
        ? model
        : typeof model === "object" && model && typeof model.primary === "string"
            ? model.primary
            : process.env.OCG_MODEL || "gpt-4o";
    const apiKey = process.env.OCG_API_KEY || "";
    const verbose = liteGw.verbose ||
        process.env.OCG_VERBOSE === "1";
    const replyChunkSize = resolveReplyChunkSize(liteGw.replyChunkSize);
    // ── Verbose: log incoming message ────────────────────────────────────
    console.log(`\n${"=".repeat(60)}`);
    console.log(`📥 [IN]  From: ${from}  |  Session: ${sessionKey}`);
    if (verbose) {
        console.log(`       Body (${body.length} chars):`);
        const preview = body.length > 2000 ? body.slice(0, 2000) + "\n... (truncated)" : body;
        console.log(preview.split("\n").map((l) => `       │ ${l}`).join("\n"));
    }
    else {
        const oneLiner = body.replace(/\n/g, "\\n");
        const preview = oneLiner.length > 200 ? oneLiner.slice(0, 200) + "..." : oneLiner;
        console.log(`       Body: ${preview}`);
    }
    console.log(`       → ${modelStr} @ ${agentUrl}`);
    // ── Mailbox transport ─────────────────────────────────────────────────
    if (agentType === "mailbox") {
        if (body.trim() === "" && extractInboundMedia(ctx).length === 0) {
            return { queuedFinal: false, counts: { tool: 0, block: 0, final: 0 } };
        }
        if (Boolean(liteGw.async) && !warnedMailboxAsyncConflict) {
            warnedMailboxAsyncConflict = true;
            console.warn("[ocg] mailbox transport takes precedence over async:true; async dispatch is ignored");
        }
        const disk = loadConfig();
        const settings = resolveMailboxSettings({
            ...cfg,
            ...(disk ?? {}),
        });
        const message = extractInboundMessage(ctx, { channelId });
        const handle = deliver
            ? { deliver, expiresAt: Date.now() + settings.replyTtlMs }
            : null;
        const mailbox = ensureMailbox({ settings });
        const entry = mailbox.enqueue(message, handle);
        console.log(`[ocg:mailbox] enqueued id=${entry.id} channel=${entry.channel} target=${entry.target ?? "(unknown)"} replyable=${entry.replyable}`);
        return { queuedFinal: false, mailbox: true, messageId: entry.id };
    }
    // Signal model selection (triggers typing indicator, etc.)
    try {
        await onModelSelected?.(modelStr);
    }
    catch {
        // Not critical
    }
    // ── ACP stdio mode ────────────────────────────────────────────────────
    if (agentType === "acp") {
        const acpCfg = {
            ...buildAcpConfigFromEnv(),
            ...resolveChannelAcpConfig(channelId, cfg),
            model: modelStr,
        };
        const agent = getAcpAgent(acpCfg);
        let fullText = "";
        let blockIndex = 0;
        const deliveredCounts = { block: 0, final: 0, tool: 0 };
        const wantFinal = (text, blocks) => finalPolicy === "always" ? true : blocks === 0 && text !== "";
        try {
            const streamBlocks = liteGw.acpStreamBlocks === true || ctx.AcpStreamBlocks === true;
            fullText = await agent.chat(sessionKey, body, {
                onDelta: async (text) => {
                    if (!streamBlocks || !text || disableBlockStreaming)
                        return;
                    const payload = { text: maybeRewriteMedia(text), isError: false };
                    let finalPayload = payload;
                    if (beforeDeliver) {
                        const result = await beforeDeliver(payload, {
                            kind: "block",
                            assistantMessageIndex: blockIndex,
                        });
                        if (result === null)
                            return;
                        if (result)
                            finalPayload = result;
                    }
                    if (deliver) {
                        deliveredCounts.block += await deliverPayloadInChunks(deliver, finalPayload, {
                            kind: "block",
                            assistantMessageIndex: blockIndex,
                        }, replyChunkSize);
                    }
                    blockIndex++;
                },
            });
            if (wantFinal(fullText, deliveredCounts.block)) {
                const finalPayload = { text: maybeRewriteMedia(fullText), isError: false };
                let finalToDeliver = finalPayload;
                if (beforeDeliver) {
                    const result = await beforeDeliver(finalPayload, {
                        kind: "final",
                        assistantMessageIndex: blockIndex,
                    });
                    if (result)
                        finalToDeliver = result;
                }
                if (deliver && finalToDeliver) {
                    deliveredCounts.final += await deliverPayloadInChunks(deliver, finalToDeliver, {
                        kind: "final",
                        assistantMessageIndex: blockIndex,
                    }, replyChunkSize);
                }
            }
            console.log(`📤 [OUT:ACP] ${fullText.length} chars, ${deliveredCounts.block} blocks`);
            console.log(`${"=".repeat(60)}\n`);
            return { queuedFinal: true, counts: deliveredCounts, acp: agent.info() };
        }
        catch (err) {
            const message = err instanceof Error ? err.message : String(err);
            console.error(`[ocg] ACP dispatch error: ${message}`);
            if (deliver) {
                await deliver({ text: `ACP agent error: ${message}`, isError: true }, {
                    kind: "final",
                    assistantMessageIndex: 0,
                }).catch(() => undefined);
            }
            return {
                queuedFinal: false,
                counts: { block: 0, final: 1, tool: 0 },
                failedCounts: { final: 1 },
            };
        }
    }
    // ── Async (fire & forget) mode ──────────────────────────────────────
    const isAsync = Boolean(liteGw.async);
    if (isAsync && deliver) {
        const { registerDeliver, buildCallbackUrl, getCallbackPort } = await import("./callback-server.js");
        const ttlMs = (liteGw.callbackTokenTTL ?? 1800) * 1000;
        const callbackToken = registerDeliver(deliver, ttlMs);
        const callbackHost = liteGw.callbackPublicHost ?? liteGw.callbackHost ?? "0.0.0.0";
        // Advertised port first, then the configured callback port, then the actual bound
        // port. The bound port may be the local listener (e.g. 3457) while the agent reaches
        // us through a proxy on another port, so it must never override an explicit port.
        const callbackPort = liteGw.callbackPublicPort ||
            liteGw.callbackPort ||
            getCallbackPort() ||
            3457;
        const callbackUrl = buildCallbackUrl(callbackHost, callbackPort, callbackToken);
        console.log(`[ocg] Async dispatch → agent, callback=${callbackUrl}`);
        const asyncHeaders = {
            "Content-Type": "application/json",
            "X-OCG-Callback": callbackUrl,
        };
        if (from && from !== "unknown") {
            // The peer this message came from, in the gateway's own target vocabulary
            // (e.g. qqbot:c2c:<openid>, qqbot:group:<id>). `user` carries the session key, which the
            // agent side cannot send back to, so a proactive send needs the target as well.
            asyncHeaders["X-OCG-Target"] = from;
        }
        if (apiKey) {
            asyncHeaders["Authorization"] = `Bearer ${apiKey}`;
        }
        // Fire & forget — do NOT await
        // Request body stays OpenAI-compatible; user carries the stable IM session key.
        fetch(agentUrl, {
            method: "POST",
            headers: asyncHeaders,
            body: JSON.stringify({
                model: modelStr,
                messages: [{ role: "user", content: body }],
                stream: false,
                user: agentUser,
            }),
            signal: AbortSignal.timeout(300_000),
        }).catch((err) => {
            console.error(`[ocg] Async forward error: ${err.message}`);
        });
        return { queuedFinal: false, async: true, callbackToken };
    }
    const headers = { "Content-Type": "application/json" };
    if (from && from !== "unknown") {
        headers["X-OCG-Target"] = from;
    }
    if (apiKey) {
        headers["Authorization"] = `Bearer ${apiKey}`;
    }
    try {
        const response = await fetch(agentUrl, {
            method: "POST",
            headers,
            body: JSON.stringify({
                model: modelStr,
                messages: [{ role: "user", content: body }],
                stream: true,
                user: agentUser,
            }),
            signal: AbortSignal.timeout(300_000),
        });
        if (!response.ok) {
            const errText = await response.text().catch(() => "");
            throw new Error(`Agent API returned ${response.status}: ${errText.slice(0, 200)}`);
        }
        const contentType = (response.headers.get("content-type") ?? "").toLowerCase();
        let fullText = "";
        let blockIndex = 0;
        let finalDelivered = false;
        const deliveredCounts = { block: 0, final: 0, tool: 0 };
        const wantFinal = (text, blocks) => finalPolicy === "always" ? true : blocks === 0 && text !== "";
        if (contentType.includes("text/event-stream")) {
            // ── SSE stream ──────────────────────────────────────────────────
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            while (true) {
                const { done, value } = await reader.read();
                if (done)
                    break;
                const chunk = decoder.decode(value, { stream: true });
                const lines = chunk.split("\n");
                for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed || !trimmed.startsWith("data:"))
                        continue;
                    const data = trimmed.slice(5).trim();
                    if (data === "[DONE]")
                        continue;
                    try {
                        const parsed = JSON.parse(data);
                        const content = parsed.choices?.[0]?.delta?.content ?? parsed.choices?.[0]?.message?.content ?? parsed.content ?? parsed.text;
                        if (!content)
                            continue;
                        fullText += content;
                        if (disableBlockStreaming)
                            continue;
                        const payload = { text: maybeRewriteMedia(content), isError: false };
                        let finalPayload = payload;
                        if (beforeDeliver) {
                            const result = await beforeDeliver(payload, {
                                kind: "block",
                                assistantMessageIndex: blockIndex,
                            });
                            if (result === null)
                                continue;
                            if (result)
                                finalPayload = result;
                        }
                        if (deliver) {
                            deliveredCounts.block += await deliverPayloadInChunks(deliver, finalPayload, {
                                kind: "block",
                                assistantMessageIndex: blockIndex,
                            }, replyChunkSize);
                        }
                        blockIndex++;
                    }
                    catch {
                        // Skip unparseable SSE lines
                    }
                }
            }
        }
        else {
            // ── Non-streaming JSON ──────────────────────────────────────────
            const raw = await response.text();
            if (verbose) {
                console.log(`[ocg] Non-streaming response (${raw.length} chars): ${raw.slice(0, 500)}`);
            }
            try {
                const parsed = JSON.parse(raw);
                // Extract content from various JSON paths
                let content = parsed?.choices?.[0]?.message?.content ??
                    parsed?.choices?.[0]?.text ??
                    parsed?.content ??
                    parsed?.response ??
                    parsed?.output ??
                    raw;
                if (typeof content !== "string") {
                    content = JSON.stringify(content);
                }
                fullText = content;
                // Deliver as final (non-streaming has no blocks)
                if (wantFinal(fullText, deliveredCounts.block)) {
                    const finalPayload = { text: maybeRewriteMedia(fullText), isError: false };
                    let finalToDeliver = finalPayload;
                    if (beforeDeliver) {
                        const result = await beforeDeliver(finalPayload, {
                            kind: "final",
                            assistantMessageIndex: 0,
                        });
                        if (result)
                            finalToDeliver = result;
                    }
                    if (deliver && finalToDeliver) {
                        deliveredCounts.final += await deliverPayloadInChunks(deliver, finalToDeliver, {
                            kind: "final",
                            assistantMessageIndex: 0,
                        }, replyChunkSize);
                        finalDelivered = true;
                    }
                }
            }
            catch {
                // Not valid JSON → treat as plain text
                fullText = raw;
                if (wantFinal(fullText, deliveredCounts.block)) {
                    const finalPayload = { text: maybeRewriteMedia(fullText), isError: false };
                    if (deliver) {
                        deliveredCounts.final += await deliverPayloadInChunks(deliver, finalPayload, {
                            kind: "final",
                            assistantMessageIndex: 0,
                        }, replyChunkSize);
                        finalDelivered = true;
                    }
                }
            }
        }
        // Deliver final payload (only if not already delivered in non-streaming path)
        if (!finalDelivered && wantFinal(fullText, deliveredCounts.block)) {
            const finalPayload = { text: maybeRewriteMedia(fullText), isError: false };
            let finalToDeliver = finalPayload;
            if (beforeDeliver) {
                const result = await beforeDeliver(finalPayload, {
                    kind: "final",
                    assistantMessageIndex: blockIndex,
                });
                if (result)
                    finalToDeliver = result;
            }
            if (deliver && finalToDeliver) {
                deliveredCounts.final += await deliverPayloadInChunks(deliver, finalToDeliver, {
                    kind: "final",
                    assistantMessageIndex: blockIndex,
                }, replyChunkSize);
                finalDelivered = true;
            }
        }
        // ── Verbose: log outgoing response ─────────────────────────────────
        console.log(`📤 [OUT] ${fullText.length} chars, ${deliveredCounts.block} blocks`);
        if (verbose) {
            const lines = fullText.split("\n");
            for (const line of lines) {
                console.log(`       │ ${line}`);
            }
        }
        else {
            const oneLiner = fullText.replace(/\n/g, "\\n");
            const preview = oneLiner.length > 300 ? oneLiner.slice(0, 300) + "..." : oneLiner;
            console.log(`       Text: ${preview}`);
        }
        console.log(`${"=".repeat(60)}\n`);
        return { queuedFinal: finalDelivered, counts: deliveredCounts };
    }
    catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        console.error(`[ocg] Agent dispatch error: ${message}`);
        // Deliver error to the user
        if (deliver) {
            try {
                await deliver({ text: `\u26a0\ufe0f Agent error: ${message}`, isError: true }, { kind: "final", assistantMessageIndex: 0 });
            }
            catch {
                // Best effort
            }
        }
        return {
            queuedFinal: false,
            counts: { block: 0, final: 1, tool: 0 },
            failedCounts: { final: 1 },
        };
    }
}
//# sourceMappingURL=inbound.js.map