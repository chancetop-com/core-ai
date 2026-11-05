import { open } from 'node:fs/promises';

const MAX_IMAGES = 10;
const MAX_IMAGE_BYTES = 6 * 1024 * 1024;

export async function buildHttpImageContent(body, media) {
    const images = media.filter((item) => !item.mime || item.mime.toLowerCase().startsWith('image/'));
    if (images.length === 0) return body;
    const parts = [];
    const forwarded = [];
    let total = 0;
    for (const image of images) {
        const bytes = await readImage(image, MAX_IMAGE_BYTES - total);
        if (bytes === null) continue;
        if (parts.length >= MAX_IMAGES) throw new Error(`At most ${MAX_IMAGES} image attachments can be forwarded`);
        total += bytes.length;
        const mime = imageMime(bytes);
        if (!mime) throw new Error('Image attachment format is not supported; use PNG, JPEG, GIF or WebP');
        parts.push({ type: 'image_url', image_url: { url: `data:${mime};base64,${bytes.toString('base64')}` } });
        forwarded.push(image);
    }
    if (parts.length === 0) return body;
    const text = withoutImageHint(body, forwarded);
    if (text.trim()) parts.unshift({ type: 'text', text });
    return parts;
}

async function readImage(image, remaining) {
    if (image.url && /^https?:\/\//i.test(image.url)) {
        throw new Error('Remote image attachment must be downloaded by the channel plugin before forwarding');
    }
    const source = image.path || image.url;
    if (!source) throw new Error('Image attachment has no readable source');
    if (source.startsWith('data:')) return decodeInlineImage(source, remaining);
    if (/^https?:\/\//i.test(source)) {
        throw new Error('Remote image attachment must be downloaded by the channel plugin before forwarding');
    }
    let file;
    try {
        file = await open(source, 'r');
        const stat = await file.stat();
        if (!stat.isFile()) throw new Error('Image attachment is not a regular file');
        const header = Buffer.alloc(12);
        const { bytesRead } = await file.read(header, 0, header.length, 0);
        if (!imageMime(header.subarray(0, bytesRead))) {
            if (!image.mime && !/\.(png|jpe?g|gif|webp)$/i.test(source)) return null;
            throw new Error('Image attachment format is not supported; use PNG, JPEG, GIF or WebP');
        }
        if (stat.size > remaining) throw imageSizeError();
        const chunks = [];
        let size = 0;
        for await (const chunk of file.createReadStream({ autoClose: false, start: 0 })) {
            size += chunk.length;
            if (size > remaining) throw imageSizeError();
            chunks.push(chunk);
        }
        return Buffer.concat(chunks, size);
    } catch (error) {
        if (error.code) throw new Error(`Image attachment could not be read (${error.code})`, { cause: error });
        throw error;
    } finally {
        await file?.close();
    }
}

function decodeInlineImage(source, remaining) {
    const match = /^data:image\/[^;,]+;base64,([A-Za-z0-9+/]*={0,2})$/i.exec(source);
    if (!match || match[1].length % 4 !== 0) throw new Error('Image attachment has invalid base64 data');
    if (match[1].length > Math.ceil(remaining / 3) * 4) throw imageSizeError();
    const bytes = Buffer.from(match[1], 'base64');
    if (bytes.length > remaining) throw imageSizeError();
    if (bytes.toString('base64') !== match[1]) throw new Error('Image attachment has invalid base64 data');
    return bytes;
}

function imageSizeError() {
    return new Error('Image attachments exceed the 6 MiB total size limit');
}

function imageMime(bytes) {
    if (bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png';
    if (bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
    if (['GIF87a', 'GIF89a'].includes(bytes.subarray(0, 6).toString('ascii'))) return 'image/gif';
    if (bytes.subarray(0, 4).toString('ascii') === 'RIFF' && bytes.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
    return null;
}

function withoutImageHint(body, images) {
    const sources = images.map((image) => image.path || image.url);
    const prefix = `- Images: ${sources.join(', ')}`;
    if (body === prefix) return '';
    if (body.startsWith(prefix + '\n\n')) return body.slice(prefix.length + 2);
    if (body.startsWith(prefix + '\n')) return body.slice(prefix.length + 1);
    return body;
}
