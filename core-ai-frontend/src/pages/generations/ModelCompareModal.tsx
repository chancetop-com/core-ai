import { useCallback, useEffect, useMemo, useState } from 'react';
import { ExternalLink, Image as ImageIcon, Loader2, Play, X } from 'lucide-react';
import { api, type ImageCompareModel, type ImageCompareRun, type MediaJob } from '../../api/client';
import { formatCostUsd, formatDuration } from '../traces/traceViewModel';

const MAX_MODELS = 4;
const CONCURRENCY = 2;
const SIZE_PRESETS = ['1024x1024', '1024x1536', '1536x1024', '1792x1024', '1024x1792'];
const QUALITY_PRESETS = ['low', 'medium', 'high', 'xhigh', 'max', 'auto'];

interface CompareTile {
  key: string;
  model: string;
  sample: number;
  status: 'pending' | 'running' | 'done' | 'error';
  run?: ImageCompareRun;
  error?: string;
}

function imageUrl(fileId?: string) {
  return fileId ? `/api/files/${fileId}/content` : '';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'generation failed';
}

/**
 * Side-by-side image model comparison for one prompt. Every run is a real billed generation
 * recorded as a normal media job, so the modal itself keeps no history — closing it discards
 * only the layout, never the evidence.
 */
export default function ModelCompareModal({ job, onClose }: { job: MediaJob; onClose: () => void }) {
  const [models, setModels] = useState<ImageCompareModel[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [prompt, setPrompt] = useState(job.prompt ?? '');
  const [size, setSize] = useState('');
  const [quality, setQuality] = useState('');
  const [samples, setSamples] = useState(1);
  const [runSamples, setRunSamples] = useState(1);
  const [tiles, setTiles] = useState<CompareTile[]>([]);
  const [running, setRunning] = useState(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    api.mediaJobs.compareModels()
      .then(list => {
        setModels(list);
        // the model that produced the image under investigation is the natural baseline
        const origin = list.find(model => model.modelId === job.requestedModel);
        setSelected(origin ? [origin.modelId] : []);
      })
      .catch(() => setModels([]));
  }, [job.requestedModel]);

  const toggleModel = useCallback((modelId: string) => {
    setSelected(prev => {
      if (prev.includes(modelId)) return prev.filter(id => id !== modelId);
      if (prev.length >= MAX_MODELS) return prev;
      return [...prev, modelId];
    });
  }, []);

  const updateTile = useCallback((key: string, patch: Partial<CompareTile>) => {
    setTiles(prev => prev.map(tile => (tile.key === key ? { ...tile, ...patch } : tile)));
  }, []);

  const run = useCallback(async () => {
    const trimmedPrompt = prompt.trim();
    if (!trimmedPrompt || selected.length === 0 || running) return;
    const tasks: CompareTile[] = [];
    for (const model of selected) {
      for (let sample = 1; sample <= samples; sample++) {
        tasks.push({ key: `${model}#${sample}`, model, sample, status: 'pending' });
      }
    }
    setTiles(tasks);
    setRunSamples(samples);
    setRunning(true);
    const queue = [...tasks];
    const worker = async () => {
      for (let task = queue.shift(); task; task = queue.shift()) {
        updateTile(task.key, { status: 'running' });
        try {
          const result = await api.mediaJobs.compare({
            prompt: trimmedPrompt,
            model: task.model,
            size: size.trim() || undefined,
            quality: quality.trim() || undefined,
          });
          updateTile(task.key, { status: 'done', run: result });
        } catch (error) {
          updateTile(task.key, { status: 'error', error: errorMessage(error) });
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, queue.length) }, worker));
    setRunning(false);
  }, [prompt, quality, running, samples, selected, size, updateTile]);

  const estimatedCost = useMemo(() => {
    if (selected.length === 0) return null;
    const perImage = selected.map(id => models.find(model => model.modelId === id)?.imagePricePerImage);
    if (perImage.some(price => price == null)) return null;
    return perImage.reduce<number>((sum, price) => sum + (price ?? 0), 0) * samples;
  }, [models, samples, selected]);

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto" style={{ background: 'rgba(0, 0, 0, 0.85)' }}
      onClick={onClose}>
      <div className="min-h-full flex justify-center p-6">
        <div className="w-full max-w-6xl h-fit rounded-xl border p-5"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
          onClick={event => event.stopPropagation()}>
          <div className="flex items-start justify-between gap-4 mb-4">
            <div>
              <h2 className="text-lg font-semibold">Compare image models</h2>
              <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                Same prompt, several models, side by side. Every run is a real generation — it is billed and
                recorded in Generations.
              </p>
            </div>
            <button onClick={onClose} className="p-1 rounded-md cursor-pointer" title="Close"
              style={{ color: 'var(--color-text-secondary)' }}>
              <X size={18} />
            </button>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-5">
            <div className="shrink-0">
              <div className="text-xs font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                Original — {job.requestedModel ?? 'unknown model'}
              </div>
              {job.fileId
                ? <a href={imageUrl(job.fileId)} target="_blank" rel="noreferrer">
                    <img src={imageUrl(job.fileId)} alt={job.fileName ?? 'original'}
                      className="w-full rounded border object-contain" style={{ borderColor: 'var(--color-border)' }} />
                  </a>
                : <div className="h-40 rounded border flex items-center justify-center"
                    style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                    <ImageIcon size={18} />
                  </div>}
            </div>

            <div className="min-w-0">
              <textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows={4}
                placeholder="Prompt to send to every selected model"
                className="w-full px-3 py-2 rounded-md border text-sm resize-y"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }} />

              <div className="flex flex-wrap items-center gap-3 mt-3 text-sm">
                <label className="flex items-center gap-1.5">
                  <span style={{ color: 'var(--color-text-secondary)' }}>Size</span>
                  <input value={size} onChange={event => setSize(event.target.value)} list="compare-sizes"
                    placeholder="model default" className="w-32 px-2 py-1 rounded-md border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }} />
                  <datalist id="compare-sizes">
                    {SIZE_PRESETS.map(preset => <option key={preset} value={preset} />)}
                  </datalist>
                </label>
                <label className="flex items-center gap-1.5">
                  <span style={{ color: 'var(--color-text-secondary)' }}>Quality</span>
                  <input value={quality} onChange={event => setQuality(event.target.value)} list="compare-qualities"
                    placeholder="model default" className="w-28 px-2 py-1 rounded-md border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }} />
                  <datalist id="compare-qualities">
                    {QUALITY_PRESETS.map(preset => <option key={preset} value={preset} />)}
                  </datalist>
                </label>
                <label className="flex items-center gap-1.5">
                  <span style={{ color: 'var(--color-text-secondary)' }}>Samples</span>
                  <select value={samples} onChange={event => setSamples(Number(event.target.value))}
                    className="px-2 py-1 rounded-md border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                    <option value={1}>1</option>
                    <option value={2}>2</option>
                  </select>
                </label>
                <div className="flex-1" />
                {estimatedCost != null && (
                  <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    ≈ {formatCostUsd(estimatedCost)} for {selected.length * samples} image(s)
                  </span>
                )}
                <button onClick={run} disabled={running || !prompt.trim() || selected.length === 0}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium cursor-pointer disabled:opacity-40"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  {running ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
                  Run {selected.length > 0 ? `${selected.length} × ${samples}` : ''}
                </button>
              </div>

              <div className="mt-3">
                <div className="text-xs font-medium mb-1.5" style={{ color: 'var(--color-text-secondary)' }}>
                  Models (up to {MAX_MODELS}) — {selected.length} selected
                </div>
                {models.length === 0 ? (
                  <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    No enabled image generation model is configured.
                  </div>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    {models.map(model => {
                      const active = selected.includes(model.modelId);
                      const blocked = !active && selected.length >= MAX_MODELS;
                      return (
                        <button key={model.modelId} onClick={() => toggleModel(model.modelId)} disabled={blocked}
                          title={model.hint ?? model.upstreamModel ?? ''}
                          className="px-2 py-1 rounded-md border text-xs cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                          style={{
                            borderColor: active ? 'var(--color-primary)' : 'var(--color-border)',
                            background: active ? 'var(--color-primary-bg)' : 'var(--color-bg-tertiary)',
                            color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                          }}>
                          {model.modelId}
                          {model.providerName ? <span style={{ opacity: 0.7 }}> · {model.providerName}</span> : null}
                          {model.imagePricePerImage != null
                            ? <span style={{ opacity: 0.7 }}> · {formatCostUsd(model.imagePricePerImage)}/img</span>
                            : null}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>

              {tiles.length > 0 && (
                <div className="grid gap-3 mt-4"
                  style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }}>
                  {tiles.map(tile => (
                    <div key={tile.key} className="rounded-lg border overflow-hidden"
                      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                      <div className="flex items-center justify-between gap-2 px-2 py-1.5 text-xs"
                        style={{ borderBottom: '1px solid var(--color-border)' }}>
                        <span className="font-medium truncate" title={tile.model}>
                          {tile.model}{runSamples > 1 ? ` #${tile.sample}` : ''}
                        </span>
                        {tile.status === 'running' && <Loader2 size={12} className="animate-spin" />}
                      </div>
                      <div className="h-48 flex items-center justify-center">
                        {tile.status === 'done' && tile.run?.fileId
                          ? <a href={imageUrl(tile.run.fileId)} target="_blank" rel="noreferrer" className="h-full w-full">
                              <img src={imageUrl(tile.run.fileId)} alt={tile.run.fileName ?? tile.model}
                                className="h-full w-full object-contain" />
                            </a>
                          : tile.status === 'error'
                            ? <div className="p-3 text-xs text-center" style={{ color: '#dc2626' }}>{tile.error}</div>
                            : tile.status === 'pending'
                              ? <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Queued</span>
                              : <Loader2 size={18} className="animate-spin" style={{ color: 'var(--color-text-secondary)' }} />}
                      </div>
                      <div className="px-2 py-1.5 text-xs flex items-center justify-between gap-2"
                        style={{ color: 'var(--color-text-secondary)', borderTop: '1px solid var(--color-border)' }}>
                        <span className="truncate" title={tile.run?.resolvedModel ?? ''}>
                          {tile.run?.resolvedModel ?? '—'}
                        </span>
                        {tile.status === 'done' && (
                          <span className="whitespace-nowrap">
                            {formatCostUsd(tile.run?.costUsd)} · {formatDuration(tile.run?.durationMs)}
                            <a href={imageUrl(tile.run?.fileId)} target="_blank" rel="noreferrer"
                              className="inline-flex align-middle ml-1.5" title="Open in new tab">
                              <ExternalLink size={11} />
                            </a>
                          </span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
