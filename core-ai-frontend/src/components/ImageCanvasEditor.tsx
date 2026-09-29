import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { PointerEvent as ReactPointerEvent, ReactNode, SyntheticEvent } from 'react';
import { Brush, Download, Eraser, Loader2, Trash2, Undo2, X } from 'lucide-react';
import { api } from '../api/client';
import type { ImageEditModel, ImageEditRequest, ImageEditResponse } from '../api/client';
import { fetchAuthedBlobUrl } from '../api/authedBlob';

const BRUSH_COLOR = 'rgba(255,64,64,0.45)';
const MAX_PROMPT = 4000;
// the server accepts a mask of at most 8 MB encoded (6 MB decoded), so oversized masks are
// rejected here instead of wasting a round trip
const MAX_MASK_DATA_URL = 8_000_000;
const MAX_MASK_EDGE = 2048;
const MIN_BRUSH_PX = 2;
const MAX_BRUSH_PX = 80;
// brush size is expressed in pixels of a 1000px-wide image, then stored as a width relative to the image
const BRUSH_WIDTH_BASE = 1000;
const ANNOTATION_CONFIRM = '该模型不支持精确圈选，将用图上标注近似，未标注区域仍可能变化。继续?';

interface Props {
  fileId?: string;
  shareToken?: string;
  sourceUrl?: string;
  src: string;
  blobUrl?: string | null;
  sessionId?: string;
  onClose: () => void;
}

interface Point {
  x: number;
  y: number;
}

// points are normalized to [0,1] of the image box and width is relative to the image width, so
// one stroke list drives both the on-screen overlay and the exported mask at any size
interface Stroke {
  points: Point[];
  width: number;
  erase: boolean;
}

interface SourceImage {
  fileId: string | null;
  shareToken: string | null;
  sourceUrl: string | null;
  src: string;
  blobUrl: string | null;
}

function canUseForRegion(model: ImageEditModel): boolean {
  return model.maskSupported || model.maskFallback === 'annotation';
}

function isAnnotationFallback(model: ImageEditModel | null): boolean {
  return !!model && !model.maskSupported && model.maskFallback === 'annotation';
}

function modelLabel(model: ImageEditModel): string {
  const name = model.displayName ? `${model.displayName} (${model.modelId})` : model.modelId;
  if (isAnnotationFallback(model)) return `${name} （标注近似）`;
  return model.maskSupported ? name : `${name} （不支持圈选）`;
}

function strokePath(ctx: CanvasRenderingContext2D, stroke: Stroke, width: number, height: number) {
  const points = stroke.points;
  if (points.length === 0) return;
  if (points.length === 1) {
    // a tap must leave a dot — a zero-length line draws nothing
    ctx.beginPath();
    ctx.arc(points[0].x * width, points[0].y * height, Math.max(1, ctx.lineWidth / 2), 0, Math.PI * 2);
    ctx.fill();
    return;
  }
  ctx.beginPath();
  ctx.moveTo(points[0].x * width, points[0].y * height);
  for (let i = 1; i < points.length; i++) ctx.lineTo(points[i].x * width, points[i].y * height);
  ctx.stroke();
}

function sourceOf(fileId?: string, shareToken?: string, sourceUrl?: string, src?: string, blobUrl?: string | null): SourceImage {
  return {
    fileId: fileId ?? null,
    shareToken: shareToken ?? null,
    sourceUrl: sourceUrl ?? null,
    src: src ?? '',
    blobUrl: blobUrl ?? null,
  };
}

/**
 * Region edit canvas: draw a mask over a platform image and send it to POST /api/media/image-edits.
 * The image is always drawn from a same-origin blob URL — an /api/files/... <img> would 307 to a
 * cross-origin signed URL and taint the canvas, making toBlob() fail.
 */
export default function ImageCanvasEditor({ fileId, shareToken, sourceUrl, src, blobUrl, sessionId, onClose }: Props) {
  const [source, setSource] = useState<SourceImage>(() => sourceOf(fileId, shareToken, sourceUrl, src, blobUrl));
  const [models, setModels] = useState<ImageEditModel[] | null>(null);
  const [modelsError, setModelsError] = useState('');
  const [modelId, setModelId] = useState('');
  const [imgUrl, setImgUrl] = useState<string | null>(null);
  const [imgError, setImgError] = useState('');
  const [naturalSize, setNaturalSize] = useState<{ width: number; height: number } | null>(null);
  const [strokes, setStrokes] = useState<Stroke[]>([]);
  const [tool, setTool] = useState<'brush' | 'eraser'>('brush');
  const [brushPx, setBrushPx] = useState(18);
  const [prompt, setPrompt] = useState('');
  const [running, setRunning] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState('');
  const [result, setResult] = useState<ImageEditResponse | null>(null);
  const [viewportTick, setViewportTick] = useState(0);
  const imgRef = useRef<HTMLImageElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawingRef = useRef(false);
  const annotationAgreedRef = useRef(false);

  useEffect(() => {
    setSource(sourceOf(fileId, shareToken, sourceUrl, src, blobUrl));
  }, [fileId, shareToken, sourceUrl, src, blobUrl]);

  useEffect(() => {
    setStrokes([]);
    setResult(null);
    setError('');
  }, [fileId]);

  useEffect(() => {
    let cancelled = false;
    api.media.imageEditModels()
      .then(response => {
        if (cancelled) return;
        const list = response.models ?? [];
        setModels(list);
        const usable = list.filter(canUseForRegion);
        const preferred = usable.find(model => model.modelId === response.defaultModelId) ?? usable[0];
        setModelId(preferred?.modelId ?? '');
      })
      .catch(err => {
        if (cancelled) return;
        setModels([]);
        setModelsError(err instanceof Error ? err.message : String(err));
      });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    let created: string | null = null;
    setImgUrl(null);
    setImgError('');
    if (source.blobUrl) {
      setImgUrl(source.blobUrl);
      return;
    }
    fetchAuthedBlobUrl(source.src)
      .then(url => {
        if (cancelled) {
          URL.revokeObjectURL(url);
          return;
        }
        created = url;
        setImgUrl(url);
      })
      // a blob URL only saves a second download; when it cannot be fetched (an upload served by the
      // CDN has no CORS headers) the image still displays and the exported mask never touches it
      .catch(() => { if (!cancelled) setImgUrl(source.src); });
    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [source]);

  useEffect(() => { setNaturalSize(null); }, [imgUrl]);

  useEffect(() => {
    const onResize = () => setViewportTick(tick => tick + 1);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    if (!running) return;
    const started = Date.now();
    setElapsed(0);
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  const drawDisplay = useCallback(() => {
    const canvas = canvasRef.current;
    const img = imgRef.current;
    if (!canvas || !img) return;
    const cssWidth = img.clientWidth;
    const cssHeight = img.clientHeight;
    if (!cssWidth || !cssHeight) return;
    const ratio = Math.min(window.devicePixelRatio || 1, MAX_MASK_EDGE / Math.max(cssWidth, cssHeight));
    canvas.width = Math.max(1, Math.round(cssWidth * ratio));
    canvas.height = Math.max(1, Math.round(cssHeight * ratio));
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, cssWidth, cssHeight);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const stroke of strokes) {
      // the eraser only removes overlay pixels, so the image behind stays visible
      ctx.globalCompositeOperation = stroke.erase ? 'destination-out' : 'source-over';
      ctx.strokeStyle = BRUSH_COLOR;
      ctx.fillStyle = BRUSH_COLOR;
      ctx.lineWidth = Math.max(1, stroke.width * cssWidth);
      strokePath(ctx, stroke, cssWidth, cssHeight);
    }
    ctx.globalCompositeOperation = 'source-over';
  }, [strokes]);

  useEffect(() => { drawDisplay(); }, [drawDisplay, naturalSize, viewportTick]);

  const pointFromEvent = (event: ReactPointerEvent<HTMLCanvasElement>): Point => {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 0;
    const y = rect.height > 0 ? (event.clientY - rect.top) / rect.height : 0;
    return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) };
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    if (!imgUrl) return;
    drawingRef.current = true;
    event.currentTarget.setPointerCapture(event.pointerId);
    const point = pointFromEvent(event);
    setStrokes(prev => [...prev, { points: [point], width: brushPx / BRUSH_WIDTH_BASE, erase: tool === 'eraser' }]);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    if (!drawingRef.current) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const point = pointFromEvent(event);
    // drop micro-moves so a long drag does not build a huge point list
    const minDistance = rect.width > 0 ? 1.5 / rect.width : 0;
    setStrokes(prev => {
      const last = prev[prev.length - 1];
      if (!last) return prev;
      const previous = last.points[last.points.length - 1];
      if (previous && Math.hypot(point.x - previous.x, point.y - previous.y) < minDistance) return prev;
      return [...prev.slice(0, -1), { ...last, points: [...last.points, point] }];
    });
  };

  const onPointerUp = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    drawingRef.current = false;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  // the exported mask is the inverse of what the user sees: an opaque black sheet with the
  // selection punched out (fully transparent areas are what the upstream model may repaint)
  const buildMask = useCallback(async (): Promise<string> => {
    const img = imgRef.current;
    if (!img || !img.naturalWidth || !img.naturalHeight) throw new Error('图片尚未加载完成，请稍后重试');
    const scale = Math.min(1, MAX_MASK_EDGE / Math.max(img.naturalWidth, img.naturalHeight));
    const width = Math.max(1, Math.round(img.naturalWidth * scale));
    const height = Math.max(1, Math.round(img.naturalHeight * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('当前浏览器不支持画布导出');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, width, height);
    ctx.globalCompositeOperation = 'destination-out';
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = '#000';
    ctx.fillStyle = '#000';
    for (const stroke of strokes) {
      ctx.lineWidth = Math.max(1, stroke.width * width);
      strokePath(ctx, stroke, width, height);
    }
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'));
    if (!blob) throw new Error('掩码导出失败，请重试');
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
      reader.onerror = () => reject(new Error('掩码导出失败，请重试'));
      reader.readAsDataURL(blob);
    });
    if (dataUrl.length > MAX_MASK_DATA_URL) throw new Error('选区过于复杂，请减少笔迹后重试');
    return dataUrl;
  }, [strokes]);

  const selectedModel = models?.find(model => model.modelId === modelId) ?? null;
  const annotationMode = isAnnotationFallback(selectedModel);
  const hasSource = !!(source.fileId || source.shareToken || source.sourceUrl);
  const canSubmit = !running && hasSource && !!selectedModel && canUseForRegion(selectedModel)
    && strokes.length > 0 && !!prompt.trim();

  const submit = useCallback(async () => {
    if (!canSubmit || !selectedModel) return;
    if (annotationMode && !annotationAgreedRef.current) {
      if (!window.confirm(ANNOTATION_CONFIRM)) return;
      annotationAgreedRef.current = true;
    }
    setRunning(true);
    setError('');
    setResult(null);
    try {
      const mask = await buildMask();
      const request: ImageEditRequest = {
        sourceFileId: source.fileId ?? undefined,
        sourceShareToken: source.shareToken ?? undefined,
        sourceUrl: source.sourceUrl ?? undefined,
        prompt: prompt.trim(),
        mask,
        model: selectedModel.modelId,
      };
      if (sessionId) request.sessionId = sessionId;
      if (annotationMode) request.maskFallback = 'annotation';
      setResult(await api.media.imageEdit(request));
    } catch (err) {
      // keep strokes and prompt so the user can switch model and retry
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setRunning(false);
    }
  }, [annotationMode, buildMask, canSubmit, prompt, selectedModel, sessionId, source.fileId, source.shareToken, source.sourceUrl]);

  const undo = () => setStrokes(prev => prev.slice(0, -1));
  const clear = () => setStrokes([]);

  const continueEditing = () => {
    if (!result?.fileId) return;
    setSource({ fileId: result.fileId, shareToken: null, sourceUrl: null, src: `/api/files/${result.fileId}/content`, blobUrl: null });
    setStrokes([]);
    setPrompt('');
    setResult(null);
    setError('');
    annotationAgreedRef.current = false;
  };

  const onImageLoad = (event: SyntheticEvent<HTMLImageElement>) => {
    const img = event.currentTarget;
    setNaturalSize({ width: img.naturalWidth, height: img.naturalHeight });
  };

  const usableCount = models?.filter(canUseForRegion).length ?? 0;
  const resultUrl = result?.url ?? (result?.fileId ? `/api/files/${result.fileId}/content` : '');

  const toolStyle = (active: boolean) => ({
    borderColor: active ? 'var(--color-primary)' : 'var(--color-border)',
    background: active ? 'var(--color-primary-bg)' : 'var(--color-bg-tertiary)',
    color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
  });

  let body: ReactNode;
  if (models === null) {
    body = (
      <div className="flex items-center gap-2 text-sm py-8" style={{ color: 'var(--color-text-secondary)' }}>
        <Loader2 size={16} className="animate-spin" /> 加载可用模型…
      </div>
    );
  } else if (usableCount === 0) {
    body = (
      <div className="text-sm py-6" style={{ color: 'var(--color-text-secondary)' }}>
        当前没有可用于圈选编辑的图片模型。需要支持掩码的模型（精确圈选），或支持图上标注近似的模型。
        {modelsError && <div className="mt-2 text-xs" style={{ color: 'var(--color-error)' }}>{modelsError}</div>}
      </div>
    );
  } else {
    body = (
      <>
        {imgError
          ? <div className="text-sm py-6" style={{ color: 'var(--color-error)' }}>图片加载失败：{imgError}</div>
          : (
            <div className="relative inline-block max-w-full">
              {imgUrl && (
                <img ref={imgRef} src={imgUrl} alt="source" onLoad={onImageLoad} draggable={false}
                  className="block max-h-[60vh] max-w-full rounded border select-none"
                  style={{ borderColor: 'var(--color-border)' }} />
              )}
              <canvas ref={canvasRef} className="absolute inset-0 h-full w-full touch-none"
                style={{ cursor: 'crosshair' }}
                onPointerDown={onPointerDown} onPointerMove={onPointerMove}
                onPointerUp={onPointerUp} onPointerCancel={onPointerUp} />
            </div>
          )}

        <div className="flex flex-wrap items-center gap-2 mt-3 text-sm">
          <button type="button" onClick={() => setTool('brush')}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md border cursor-pointer"
            style={toolStyle(tool === 'brush')}>
            <Brush size={14} /> 画笔
          </button>
          <button type="button" onClick={() => setTool('eraser')}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md border cursor-pointer"
            style={toolStyle(tool === 'eraser')}>
            <Eraser size={14} /> 橡皮
          </button>
          <label className="inline-flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
            粗细
            <input type="range" min={MIN_BRUSH_PX} max={MAX_BRUSH_PX} value={brushPx}
              onChange={event => setBrushPx(Number(event.target.value))} />
          </label>
          <div className="flex-1" />
          <button type="button" onClick={undo} disabled={strokes.length === 0}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md border cursor-pointer disabled:opacity-40"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
            <Undo2 size={14} /> 撤销
          </button>
          <button type="button" onClick={clear} disabled={strokes.length === 0}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md border cursor-pointer disabled:opacity-40"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
            <Trash2 size={14} /> 清空
          </button>
        </div>

        {annotationMode && (
          <div className="mt-2 text-xs" style={{ color: 'var(--color-warning)' }}>
            近似模式：该模型用图上标注近似圈选，边界为提示词级，不保证只改框内。
          </div>
        )}

        <div className="mt-3">
          <textarea value={prompt} onChange={event => setPrompt(event.target.value)} maxLength={MAX_PROMPT} rows={3}
            placeholder="要改成什么？（例如：把这块的夹克换成红色皮衣）"
            className="w-full px-3 py-2 rounded-md border text-sm resize-y"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }} />
          <div className="flex flex-wrap items-center gap-3 mt-2 text-sm">
            <label className="flex items-center gap-1.5">
              <span style={{ color: 'var(--color-text-secondary)' }}>模型</span>
              <select value={modelId} onChange={event => setModelId(event.target.value)}
                className="px-2 py-1.5 rounded-md border text-sm max-w-[360px]"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                {models.map(model => (
                  <option key={model.modelId} value={model.modelId} disabled={!canUseForRegion(model)}>
                    {modelLabel(model)}
                  </option>
                ))}
              </select>
            </label>
            <div className="flex-1" />
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {prompt.length}/{MAX_PROMPT}
            </span>
            <button type="button" onClick={submit} disabled={!canSubmit}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              {running && <Loader2 size={14} className="animate-spin" />} 生成
            </button>
          </div>
          {running && (
            <div className="mt-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              生成中… 已用 {elapsed}s（图片生成通常 20–90s，请勿重复提交，重复生成会再次计费）
            </div>
          )}
        </div>

        {error && (
          <div className="mt-3 rounded border p-2 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>
            {error}
          </div>
        )}

        {result && (
          <div className="mt-4">
            <div className="text-sm font-medium mb-2">结果</div>
            {result.maskMode === 'annotation' && (
              <div className="mb-2 text-xs" style={{ color: 'var(--color-warning)' }}>本次为标注近似，不是精确圈选</div>
            )}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>原图</div>
                <img src={source.src} alt="source" className="w-full rounded border object-contain"
                  style={{ borderColor: 'var(--color-border)' }} />
              </div>
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>结果</div>
                {resultUrl
                  ? <img src={resultUrl} alt="result" className="w-full rounded border object-contain"
                      style={{ borderColor: 'var(--color-border)' }} />
                  : <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>服务端未返回结果文件</div>}
              </div>
            </div>
            <div className="mt-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              只重绘圈选区域；未圈选区域会有一次重采样级差异，不是像素级不变。
            </div>
            {result.notes && result.notes.length > 0 && (
              <ul className="mt-1 text-xs list-disc pl-4" style={{ color: 'var(--color-text-secondary)' }}>
                {result.notes.map((note, index) => <li key={index}>{note}</li>)}
              </ul>
            )}
            <div className="flex flex-wrap items-center gap-2 mt-3">
              <button type="button" onClick={continueEditing} disabled={!result.fileId}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)', color: 'white' }}>
                继续编辑
              </button>
              {result.fileId && (
                <a href={`/api/files/${result.fileId}/content`} download target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border text-sm cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                  <Download size={14} /> 下载
                </a>
              )}
              <button type="button" onClick={onClose}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border text-sm cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                关闭
              </button>
            </div>
          </div>
        )}
      </>
    );
  }

  // a portal keeps the overlay viewport-anchored: rendered inside a chat message the message list
  // becomes the containing block and a "fixed" child would be laid out at the message position
  return createPortal(
    <div className="fixed inset-0 z-[1000] overflow-y-auto" style={{ background: 'rgba(0,0,0,0.8)' }}
      onClick={onClose}>
      <div className="min-h-full flex items-center justify-center p-4">
        <div className="w-full max-w-4xl rounded-xl border p-5"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
          onClick={event => event.stopPropagation()}>
          <div className="flex items-start justify-between gap-4 mb-3">
            <div>
              <h2 className="text-lg font-semibold">圈选编辑</h2>
              <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                在图上圈出要修改的区域，写下要改成什么；只有圈选区域会被重绘。
              </p>
            </div>
            <button type="button" onClick={onClose} className="p-1 rounded-md cursor-pointer" title="Close"
              style={{ color: 'var(--color-text-secondary)' }}>
              <X size={18} />
            </button>
          </div>
          {body}
        </div>
      </div>
    </div>,
    document.body
  );
}
