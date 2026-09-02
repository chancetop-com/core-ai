import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, DollarSign, Film, Image as ImageIcon, Play } from 'lucide-react';
import { api } from '../../api/client';
import type { MediaJob } from '../../api/client';
import { formatCostUsd } from '../traces/traceViewModel';

const SOURCE_COLORS: Record<string, { bg: string; text: string }> = {
  gateway_model: { bg: '#ede9fe', text: '#7c3aed' },
  upstream: { bg: '#dbeafe', text: '#2563eb' },
  model_catalog: { bg: '#dcfce7', text: '#16a34a' },
  unavailable: { bg: '#f1f5f9', text: '#94a3b8' },
};

const STATE_COLORS: Record<string, { bg: string; text: string }> = {
  completed: { bg: '#dcfce7', text: '#16a34a' },
  processing: { bg: '#dbeafe', text: '#2563eb' },
  queued: { bg: '#fef3c7', text: '#b45309' },
  submitted: { bg: '#fef3c7', text: '#b45309' },
  failed: { bg: '#fee2e2', text: '#dc2626' },
  cancelled: { bg: '#f1f5f9', text: '#64748b' },
};

function formatTime(iso?: string) {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function unitsLabel(job: MediaJob) {
  if (job.mediaUnits == null) {
    if (job.creditsConsumed != null) return `${job.creditsConsumed} credits`;
    return '-';
  }
  const type = job.mediaUnitType ?? '';
  if (type === 'second') return `${job.mediaUnits}s`;
  if (type === 'image') return `${job.mediaUnits} images`;
  if (type === 'token') return `${job.mediaUnits} tokens`;
  return String(job.mediaUnits);
}

interface MediaJobResult {
  requestKey: string;
  jobs: MediaJob[];
  total: number;
}

export default function Generations() {
  const [result, setResult] = useState<MediaJobResult>({ requestKey: '', jobs: [], total: 0 });
  const [error, setError] = useState('');
  const [offset, setOffset] = useState(0);
  const [mediaType, setMediaType] = useState('');
  const [costSource, setCostSource] = useState('');
  const [preview, setPreview] = useState<MediaJob | null>(null);
  const limit = 20;

  useEffect(() => {
    if (!preview) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setPreview(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [preview]);
  const requestKey = JSON.stringify({ offset, mediaType, costSource });
  const loading = result.requestKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    api.mediaJobs.list(offset, limit, { mediaType: mediaType || undefined, costSource: costSource || undefined })
      .then(response => {
        if (!cancelled) {
          setResult({ requestKey, jobs: response.jobs, total: response.total });
          setError('');
        }
      })
      .catch(e => {
        if (!cancelled) {
          setResult({ requestKey, jobs: [], total: 0 });
          setError(e.message);
        }
      });
    return () => { cancelled = true; };
  }, [offset, mediaType, costSource, requestKey]);

  const jobs = result.jobs;
  const total = result.total;
  const pageCost = jobs.reduce((sum, job) => sum + (job.costUsd ?? 0), 0);

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Generations</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Image / video generations with their cost ({total} total, page sum {formatCostUsd(pageCost)})
          </p>
        </div>
        <div className="flex gap-2">
          <select
            value={mediaType}
            onChange={e => { setMediaType(e.target.value); setOffset(0); }}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <option value="">All types</option>
            <option value="image">Image</option>
            <option value="video">Video</option>
          </select>
          <select
            value={costSource}
            onChange={e => { setCostSource(e.target.value); setOffset(0); }}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <option value="">All sources</option>
            <option value="gateway_model">Gateway model</option>
            <option value="upstream">Upstream</option>
            <option value="model_catalog">Model catalog</option>
            <option value="unavailable">Unavailable</option>
          </select>
        </div>
      </div>

      {error && <div className="mb-4 rounded border border-red-300 bg-red-50 p-2 text-sm text-red-700">{error}</div>}

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Output</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Time</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Type</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>State</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Model</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Usage</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Source</th>
              <th className="text-right px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="inline-flex items-center gap-1"><DollarSign size={14} /> Cost</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : jobs.length === 0 ? (
              <tr><td colSpan={8} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>No media generations found</td></tr>
            ) : jobs.map(job => {
              const sourceColor = SOURCE_COLORS[job.costSource ?? ''] ?? SOURCE_COLORS.unavailable;
              const stateColor = STATE_COLORS[job.state ?? ''] ?? STATE_COLORS.submitted;
              const isImage = job.mediaType === 'image';
              return (
                <tr key={job.id} className="border-t" style={{ borderColor: 'var(--color-border)' }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                  onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                  <td className="px-4 py-2">
                    {isImage && job.fileId
                      ? <img src={`/api/files/${job.fileId}/content`} alt={job.fileName ?? 'generated'}
                          onClick={() => setPreview(job)} title="Click to view"
                          className="h-16 w-12 rounded border object-cover cursor-pointer" style={{ borderColor: 'var(--color-border)' }} />
                      : isImage
                        ? <div className="h-16 w-12 rounded border flex items-center justify-center" style={{ borderColor: 'var(--color-border)' }}>
                            <ImageIcon size={14} style={{ color: 'var(--color-text-secondary)' }} />
                          </div>
                        : job.state === 'completed'
                          ? <div onClick={() => setPreview(job)} title="Click to play"
                              className="h-16 w-28 rounded border flex items-center justify-center cursor-pointer"
                              style={{ borderColor: 'var(--color-border)', background: '#0f172a' }}>
                              <Play size={20} color="#f8fafc" fill="#f8fafc" />
                            </div>
                          : <div className="h-16 w-28 rounded border flex items-center justify-center" style={{ borderColor: 'var(--color-border)' }}>
                              <Film size={14} style={{ color: 'var(--color-text-secondary)' }} />
                            </div>}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(job.createdAt)}</td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium"
                      style={{ background: 'var(--color-bg-tertiary)' }}>
                      {isImage ? <ImageIcon size={12} /> : <Film size={12} />}
                      {isImage ? 'image' : 'video'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                      title={job.error ?? ''}
                      style={{ background: stateColor.bg, color: stateColor.text }}>{job.state ?? '-'}</span>
                    {job.state === 'failed' && job.error && (
                      <div className="text-xs mt-1 truncate" title={job.error}
                        style={{ color: '#dc2626', maxWidth: '200px' }}>{job.error}</div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="font-medium truncate" style={{ maxWidth: '160px' }}>{job.requestedModel ?? '-'}</div>
                    {job.resolvedModel && job.resolvedModel !== job.requestedModel && (
                      <div className="text-xs mt-0.5 font-mono truncate" style={{ color: 'var(--color-text-secondary)', maxWidth: '160px' }}>
                        {job.resolvedModel}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{unitsLabel(job)}</td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono"
                      style={{ background: sourceColor.bg, color: sourceColor.text }}
                      title={job.pricingModelId ?? ''}>
                      {job.costSource ?? '-'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right font-medium">{formatCostUsd(job.costUsd)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {jobs.length > 0 ? offset + 1 : 0}-{offset + jobs.length} of {total}
        </span>
        <div className="flex gap-2">
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={offset + jobs.length >= total}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>

      {preview && (
        <div className="fixed inset-0 z-50 flex flex-col items-center justify-center p-8"
          style={{ background: 'rgba(0, 0, 0, 0.85)' }} onClick={() => setPreview(null)}>
          {preview.mediaType === 'image' && preview.fileId
            ? <img src={`/api/files/${preview.fileId}/content`} alt={preview.fileName ?? 'generated'}
                className="max-h-[85vh] max-w-[90vw] rounded object-contain" onClick={e => e.stopPropagation()} />
            : <video src={`/api/media-jobs/${preview.id}/content`} controls autoPlay
                className="max-h-[85vh] max-w-[90vw] rounded" onClick={e => e.stopPropagation()} />}
          <div className="mt-3 text-sm" style={{ color: '#cbd5e1' }} onClick={e => e.stopPropagation()}>
            {preview.requestedModel} · {formatTime(preview.createdAt)}
            <a href={preview.mediaType === 'image' && preview.fileId
                ? `/api/files/${preview.fileId}/content` : `/api/media-jobs/${preview.id}/content`}
              target="_blank" rel="noreferrer" className="ml-3 underline">Open in new tab</a>
          </div>
        </div>
      )}
    </div>
  );
}
