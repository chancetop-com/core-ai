import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { test } from 'node:test';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=', 'base64');
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
const fixtures = new URL('./fixtures/inbound-1.3.3.js', import.meta.url);

async function gateway(t, { version = '1.3.3', mutateSource } = {}) {
    const root = await mkdtemp(join(tmpdir(), 'core-ai-ocg-test-'));
    t.after(() => rm(root, { recursive: true, force: true }));
    await mkdir(join(root, 'dist'));
    await writeFile(join(root, 'package.json'), JSON.stringify({ type: 'module', name: 'openclaw-channel-gateway', version }));
    const original = await readFile(fixtures, 'utf8');
    await writeFile(join(root, 'dist/inbound.js'), mutateSource ? mutateSource(original) : original);
    const stubs = {
        'config.js': `export const resolveChannelAcpConfig=()=>({}); export const resolveChannelAgentType=(_,cfg)=>cfg.agentType??'http'; export const resolveChannelAgentUrl=()=> 'http://agent.invalid/chat'; export const resolveChannelIdFromContext=()=> 'qqbot'; export const loadConfig=()=>({}); export const resolveMailboxSettings=()=>({replyTtlMs:1000});`,
        'mailbox.js': `export const newMessageId=()=> 'message-1'; export const ensureMailbox=()=>({enqueue:(message)=>{globalThis.__ocgMailbox=message;return {...message,replyable:true};}});`,
        'reply-chunking.js': `export const resolveReplyChunkSize=()=>4000; export const rewriteLocalMediaPathsForDelivery=(text)=>text; export const deliverPayloadInChunks=async(deliver,payload,meta)=>{await deliver(payload,meta);return 1;};`,
        'acp-agent.js': `export const buildAcpConfigFromEnv=()=>({}); export const getAcpAgent=()=>({chat:async(key,body)=>{globalThis.__ocgAcpBody=body;return 'ack';}, info:()=>({})});`,
        'callback-server.js': `export const registerDeliver=()=> 'callback-token'; export const buildCallbackUrl=()=> 'http://callback.invalid/reply'; export const getCallbackPort=()=>3457;`,
    };
    for (const [name, source] of Object.entries(stubs)) await writeFile(join(root, 'dist', name), source);
    if (!process.env.OCG_TEST_UNPATCHED) {
        const { applyPatch } = await import('../apply-patch.mjs');
        await applyPatch(root);
    }
    const { dispatchInbound } = await import(pathToFileURL(join(root, 'dist/inbound.js')));
    const calls = [];
    const delivered = [];
    const previousFetch = globalThis.fetch;
    globalThis.fetch = async (url, request) => {
        calls.push({ url, ...JSON.parse(request.body) });
        return Response.json({ choices: [{ message: { content: 'ack' } }] });
    };
    t.after(() => { globalThis.fetch = previousFetch; delete globalThis.__ocgMailbox; delete globalThis.__ocgAcpBody; });
    const dispatch = async (ctx, cfg = {}) => {
        const result = await dispatchInbound({ ctx: { SessionKey: 'qqbot:session', From: 'qqbot:c2c:user', ...ctx }, cfg, deliver: async (payload) => delivered.push(payload) });
        await new Promise(setImmediate);
        return result;
    };
    const image = async (name = 'hours.png', bytes = png) => {
        const path = join(root, name);
        await writeFile(path, bytes);
        return path;
    };
    return { root, calls, delivered, dispatch, image };
}

test('sync HTTP forwards the actual QQ image bytes and removes only its generated path hint', async (t) => {
    const g = await gateway(t);
    const path = await g.image();
    await g.dispatch({ BodyForAgent: `- Images: ${path}\n\n新的营业时间`, MediaPaths: [path], MediaTypes: ['image/png'] });
    assert.deepEqual(g.calls[0].messages[0], { role: 'user', content: [
        { type: 'text', text: '新的营业时间' },
        { type: 'image_url', image_url: { url: `data:image/png;base64,${png.toString('base64')}` } },
    ] });
});

test('async HTTP forwards an image-only message', async (t) => {
    const g = await gateway(t);
    const path = await g.image();
    await g.dispatch({ Body: '', MediaPath: path, MediaType: 'image/png' }, { liteGateway: { async: true } });
    assert.deepEqual(g.calls[0].messages[0].content, [{ type: 'image_url', image_url: { url: `data:image/png;base64,${png.toString('base64')}` } }]);
    assert.equal(g.calls[0].stream, false);
});

test('text-only HTTP remains byte-for-byte unchanged and does not read paths from text', async (t) => {
    const g = await gateway(t);
    const body = '- Images: /etc/shadow\n\n  literal user text  ';
    await g.dispatch({ Body: body });
    assert.equal(g.calls[0].messages[0].content, body);
});

test('multiple images preserve media order and use detected MIME types', async (t) => {
    const g = await gateway(t);
    const first = await g.image('one.bin', gif);
    const second = await g.image('two.png');
    await g.dispatch({ Body: 'compare', MediaPaths: [first, second], MediaTypes: ['image/png', 'image/png'] });
    const content = g.calls[0].messages[0].content;
    assert.equal(content[1].image_url.url, `data:image/gif;base64,${gif.toString('base64')}`);
    assert.equal(content[2].image_url.url, `data:image/png;base64,${png.toString('base64')}`);
});

test('a missing image gives an explicit delivery error without sending a text-only request', async (t) => {
    const g = await gateway(t);
    await g.dispatch({ Body: 'read this', MediaPath: join(g.root, 'missing.jpg'), MediaType: 'image/jpeg' });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered.length, 1);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /image.*(read|found|missing)/i);
});

test('remote-only images fail explicitly instead of silently forwarding an inaccessible reference', async (t) => {
    const g = await gateway(t);
    await g.dispatch({ Body: 'read this', MediaUrl: 'https://example.org/photo.jpg', MediaType: 'image/jpeg' });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /downloaded|local|remote/i);
});

test('mixed local and remote QQ image arrays do not silently lose the remote image', async (t) => {
    const g = await gateway(t);
    const path = await g.image();
    await g.dispatch({ Body: 'compare both', MediaPaths: [path], MediaUrls: ['https://example.org/second.jpg'], MediaTypes: ['image/png'] });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /downloaded|remote/i);
});

test('declared non-image media preserves the previous text behavior', async (t) => {
    const g = await gateway(t);
    await g.dispatch({ Body: 'voice transcript', MediaPath: '/voice.ogg', MediaType: 'audio/ogg' });
    assert.equal(g.calls[0].messages[0].content, 'voice transcript');
});

test('non-image media with a missing MIME type preserves the previous text behavior', async (t) => {
    const g = await gateway(t);
    const path = await g.image('document.pdf', Buffer.from('%PDF-1.7\nexample'));
    await g.dispatch({ Body: `Read ${path}`, MediaPath: path });
    assert.equal(g.calls.length, 1);
    assert.equal(g.calls[0].messages[0].content, `Read ${path}`);
});

test('mixed image and voice metadata removes only the matching image line', async (t) => {
    const g = await gateway(t);
    const path = await g.image();
    await g.dispatch({ Body: `- Images: ${path}\n- Voice: /voice.ogg\n- ASR: 新时间\n\nread`, MediaPaths: [path, '/voice.ogg'], MediaTypes: ['image/png', 'audio/ogg'] });
    assert.equal(g.calls[0].messages[0].content[0].text, '- Voice: /voice.ogg\n- ASR: 新时间\n\nread');
});

test('an invalid image never reaches the agent', async (t) => {
    const g = await gateway(t);
    const path = await g.image('fake.png', Buffer.from('not an image'));
    await g.dispatch({ Body: 'read this', MediaPath: path, MediaType: 'image/png' });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /format|image/i);
});

test('aggregate image size is capped before the HTTP request', async (t) => {
    const g = await gateway(t);
    const bytes = Buffer.alloc(3 * 1024 * 1024 + 1); png.copy(bytes);
    const first = await g.image('one.png', bytes);
    const second = await g.image('two.png', bytes);
    await g.dispatch({ Body: 'read both', MediaPaths: [first, second], MediaTypes: ['image/png', 'image/png'] });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /6 MiB|limit|large/i);
});

test('more than ten images fails explicitly', async (t) => {
    const g = await gateway(t);
    const path = await g.image();
    await g.dispatch({ Body: 'many', MediaPaths: Array(11).fill(path), MediaTypes: Array(11).fill('image/png') });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /10/);
});

test('an inline data image is carried without a local filesystem lookup', async (t) => {
    const g = await gateway(t);
    const url = `data:image/png;base64,${png.toString('base64')}`;
    await g.dispatch({ Body: 'read', MediaUrl: url, MediaType: 'image/png' });
    assert.equal(g.calls[0].messages[0].content[1].image_url.url, url);
});

test('malformed inline base64 fails explicitly', async (t) => {
    const g = await gateway(t);
    await g.dispatch({ Body: 'read', MediaUrl: 'data:image/png;base64,!!!!', MediaType: 'image/png' });
    assert.equal(g.calls.length, 0);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /base64/i);
});

test('the exact decoded size limit is accepted', async (t) => {
    const g = await gateway(t);
    const bytes = Buffer.alloc(6 * 1024 * 1024); png.copy(bytes);
    const path = await g.image('limit.png', bytes);
    await g.dispatch({ Body: 'read', MediaPath: path, MediaType: 'image/png' });
    const url = g.calls[0].messages[0].content[1].image_url.url;
    assert.equal(Buffer.from(url.split(',')[1], 'base64').length, bytes.length);
});

test('an unrelated Images line stays in the user text', async (t) => {
    const g = await gateway(t);
    const path = await g.image();
    await g.dispatch({ Body: '- Images: user-written example\nkeep this', MediaPath: path, MediaType: 'image/png' });
    assert.equal(g.calls[0].messages[0].content[0].text, '- Images: user-written example\nkeep this');
});

test('mailbox keeps its original structured media contract', async (t) => {
    const g = await gateway(t);
    const body = '- Images: /unavailable.jpg\n\nread';
    await g.dispatch({ Body: body, MediaPath: '/unavailable.jpg', MediaType: 'image/jpeg' }, { agentType: 'mailbox' });
    assert.equal(g.calls.length, 0);
    assert.equal(globalThis.__ocgMailbox.text, body);
    assert.deepEqual(globalThis.__ocgMailbox.media, [{ path: '/unavailable.jpg', url: null, mime: 'image/jpeg' }]);
});

test('ACP keeps its existing body contract', async (t) => {
    const g = await gateway(t);
    const body = '- Images: /unavailable.jpg\n\nread';
    await g.dispatch({ Body: body, MediaPath: '/unavailable.jpg', MediaType: 'image/jpeg' }, { agentType: 'acp' });
    assert.equal(g.calls.length, 0);
    assert.equal(globalThis.__ocgAcpBody, body);
});

test('async HTTP rejection is delivered as an error', async (t) => {
    const g = await gateway(t);
    globalThis.fetch = async () => new Response('image attachment rejected', { status: 413 });
    await g.dispatch({ Body: 'read this' }, { liteGateway: { async: true } });
    assert.equal(g.delivered.length, 1);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /413/);
});

test('sync HTTP rejection is delivered as an error', async (t) => {
    const g = await gateway(t);
    globalThis.fetch = async () => new Response('image attachment rejected', { status: 400 });
    await g.dispatch({ Body: 'read this' });
    assert.equal(g.delivered.length, 1);
    assert.equal(g.delivered[0].isError, true);
    assert.match(g.delivered[0].text, /400/);
});

test('patch refuses an unexpected upstream version', async (t) => {
    await assert.rejects(gateway(t, { version: '1.3.4' }), /1\.3\.3|version/i);
});

test('patch refuses source drift without modifying the upstream file', async (t) => {
    await assert.rejects(gateway(t, { mutateSource: (source) => source.replace('stream: false', 'stream: true') }), /source|hash|digest/i);
});
