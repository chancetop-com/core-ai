import { createHash } from 'node:crypto';
import { copyFile, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const VERSION = '1.3.3';
const INBOUND_SHA256 = '0f6e35f9db95220dc746f11e3305464cb9af9563f222bd3685ce8db3da10783e';

export async function applyPatch(packageRoot) {
    const metadata = JSON.parse(await readFile(join(packageRoot, 'package.json'), 'utf8'));
    if (metadata.name !== 'openclaw-channel-gateway' || metadata.version !== VERSION) {
        throw new Error(`OCG image patch requires openclaw-channel-gateway@${VERSION}`);
    }
    const inboundPath = join(packageRoot, 'dist/inbound.js');
    let source = await readFile(inboundPath, 'utf8');
    if (createHash('sha256').update(source).digest('hex') !== INBOUND_SHA256) {
        throw new Error('OCG inbound source digest changed; review the image forwarding patch before building');
    }
    source = replaceExactly(source,
        'import { buildAcpConfigFromEnv, getAcpAgent } from "./acp-agent.js";',
        'import { buildAcpConfigFromEnv, getAcpAgent } from "./acp-agent.js";\nimport { buildHttpImageContent } from "./core-ai-http-image-content.mjs";', 1);
    source = replaceExactly(source,
        '    // ── Async (fire & forget) mode ──────────────────────────────────────',
        `    let httpContent;
    try {
        httpContent = await buildHttpImageContent(body, extractInboundMedia(ctx));
    } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        console.error(\`[ocg] Image attachment error: \${message}\`);
        if (deliver) {
            await deliver({ text: \`Image attachment error: \${message}\`, isError: true }, {
                kind: "final", assistantMessageIndex: 0,
            }).catch(() => undefined);
        }
        return { queuedFinal: false, counts: { tool: 0, block: 0, final: deliver ? 1 : 0 }, failedCounts: { final: 1 } };
    }
    // ── Async (fire & forget) mode ──────────────────────────────────────`, 1);
    source = replaceExactly(source,
        'messages: [{ role: "user", content: body }]',
        'messages: [{ role: "user", content: httpContent }]', 2);
    source = replaceExactly(source,
        `        }).catch((err) => {
            console.error(\`[ocg] Async forward error: \${err.message}\`);
        });`,
        `        }).then(async (response) => {
            if (!response.ok) throw new Error(\`Agent API returned \${response.status}\`);
        }).catch(async (err) => {
            console.error(\`[ocg] Async forward error: \${err.message}\`);
            await deliver({ text: \`Agent request failed: \${err.message}\`, isError: true }, {
                kind: "final", assistantMessageIndex: 0,
            }).catch(() => undefined);
        });`, 1);
    await copyFile(new URL('./http-image-content.mjs', import.meta.url), join(packageRoot, 'dist/core-ai-http-image-content.mjs'));
    await writeFile(inboundPath, source);
}

function replaceExactly(source, before, after, expected) {
    const count = source.split(before).length - 1;
    if (count !== expected) throw new Error(`OCG patch expected ${expected} matching sites, found ${count}`);
    return source.replaceAll(before, after);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    if (!process.argv[2]) throw new Error('Usage: node apply-patch.mjs <installed OCG package directory>');
    await applyPatch(process.argv[2]);
    console.log(`Applied Core AI HTTP image forwarding patch to OCG ${VERSION}`);
}
