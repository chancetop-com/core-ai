import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { AlertTriangle, Film, Loader2, Play, RotateCcw, Volume2 } from 'lucide-react';
import type { MediaJob } from '../api/client';

interface VideoPlayerProps {
  job: MediaJob;
  autoPlay?: boolean;
  className?: string;
}

function StatePanel({ title, description, icon, action }: {
  title: string;
  description?: string;
  icon: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex min-h-[240px] w-full max-w-[90vw] flex-col items-center justify-center gap-3 rounded-lg border p-8 text-center"
      style={{ borderColor: '#334155', background: '#0f172a' }} onClick={e => e.stopPropagation()}>
      {icon}
      <div className="text-sm font-medium" style={{ color: '#e2e8f0' }}>{title}</div>
      {description && <div className="max-w-md text-xs" style={{ color: '#94a3b8' }}>{description}</div>}
      {action}
    </div>
  );
}

function posterUrl(job: MediaJob) {
  if (job.posterUrl) return job.posterUrl;
  return job.posterFileId ? `/api/files/${job.posterFileId}/content` : null;
}

export default function VideoPlayer({ job, autoPlay = false, className = '' }: VideoPlayerProps) {
  const [started, setStarted] = useState(autoPlay);
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [muted, setMuted] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const [autoRetries, setAutoRetries] = useState(0);
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    setStarted(autoPlay);
    setState(autoPlay ? 'loading' : 'idle');
    setMuted(true);
    setAttempt(0);
    setAutoRetries(0);
  }, [job.id, autoPlay]);

  useEffect(() => {
    if (started) setState('loading');
  }, [started, attempt]);

  useEffect(() => {
    if (!started || state !== 'error' || job.artifactState === 'expired' || autoRetries >= 3) return;
    const timer = setTimeout(() => {
      setAutoRetries(value => value + 1);
      setAttempt(value => value + 1);
    }, 3000);
    return () => clearTimeout(timer);
  }, [started, state, job.artifactState, autoRetries]);

  if (job.state === 'failed') {
    return <StatePanel title="视频生成失败" description={job.error || '上游没有返回可用的视频。'}
      icon={<AlertTriangle size={28} color="#f87171" />} />;
  }
  if (job.state !== 'completed') {
    return <StatePanel title={`视频${job.state === 'processing' ? '生成中' : '排队中'}`}
      description={job.progress != null ? `进度 ${job.progress}%` : '请稍后刷新'}
      icon={<Loader2 size={28} className="animate-spin" color="#93c5fd" />} />;
  }
  if (job.artifactState === 'expired') {
    return <StatePanel title="视频已过期"
      description={job.artifactError || '原始视频在上游的保留期已结束，且该任务完成时没有归档，无法再播放。'}
      icon={<Film size={28} color="#94a3b8" />} />;
  }
  if (job.artifactState === 'failed') {
    return <StatePanel title="视频归档失败"
      description={job.artifactError || '视频字节暂时无法保存到平台存储，请稍后刷新重试。'}
      icon={<AlertTriangle size={28} color="#fbbf24" />}
      action={<button onClick={() => setAttempt(value => value + 1)}
        className="inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-xs cursor-pointer"
        style={{ borderColor: '#475569', color: '#e2e8f0', background: 'rgba(255,255,255,0.08)' }}>
        <RotateCcw size={13} /> 重试
      </button>} />;
  }

  const src = job.contentUrl || `/api/media-jobs/${job.id}/content`;
  const poster = posterUrl(job);
  const pending = !job.playable;

  if (!started) {
    return (
      <div className={`relative flex items-center justify-center ${className}`} onClick={e => e.stopPropagation()}>
        <button type="button" onClick={() => setStarted(true)}
          className="group relative block overflow-hidden rounded-lg cursor-pointer"
          style={{ background: '#0f172a' }}
          title={pending ? '点击播放；首次播放会把视频归档到平台存储' : '点击播放'}>
          {poster
            ? <img src={poster} alt={job.fileName ?? '视频封面'}
                className="max-h-[80vh] max-w-[90vw] rounded object-contain" loading="lazy" />
            : <div className="flex aspect-video w-[min(80vw,720px)] max-w-[90vw] flex-col items-center justify-center gap-2 rounded"
                style={{ background: 'linear-gradient(145deg, #1e293b, #0f172a)' }}>
                <Film size={34} color="#64748b" />
                <span className="text-xs" style={{ color: '#94a3b8' }}>
                  {pending ? '封面生成中' : '暂无封面'}
                </span>
              </div>}
          <span className="absolute inset-0 flex items-center justify-center bg-black/20 transition group-hover:bg-black/35">
            <span className="flex h-16 w-16 items-center justify-center rounded-full shadow-lg"
              style={{ background: 'rgba(15,23,42,0.85)', border: '1px solid rgba(226,232,240,0.5)' }}>
              <Play size={28} color="#f8fafc" fill="#f8fafc" />
            </span>
          </span>
          <span className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full px-3 py-1 text-xs whitespace-nowrap"
            style={{ background: 'rgba(15,23,42,0.82)', color: '#e2e8f0', border: '1px solid #475569' }}>
            {pending ? '点击播放 · 首次播放自动归档' : '点击播放'}
          </span>
        </button>
      </div>
    );
  }

  return (
    <div className={`relative flex items-center justify-center ${className}`} onClick={e => e.stopPropagation()}>
      <video
        key={`${job.id}-${attempt}`}
        ref={videoRef}
        src={src}
        poster={poster ?? undefined}
        controls
        autoPlay
        muted={muted}
        playsInline
        preload="auto"
        className="max-h-[80vh] max-w-[90vw] rounded"
        onLoadedData={() => setState('ready')}
        onError={() => setState('error')}
        onVolumeChange={() => setMuted(videoRef.current?.muted ?? true)}
      />
      {state === 'loading' && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded bg-slate-950/70 text-xs"
          style={{ color: '#cbd5e1' }}>
          <Loader2 size={24} className="animate-spin" />
          {pending ? '视频首次加载会自动归档，可能需要几秒…' : '加载视频…'}
        </div>
      )}
      {state === 'error' && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded bg-slate-950/85 px-6 text-center text-xs"
          style={{ color: '#e2e8f0' }}>
          <AlertTriangle size={24} color="#fbbf24" />
          <div className="font-medium">{pending ? '视频正在归档，请稍后重试' : '视频加载失败'}</div>
          <div style={{ color: '#94a3b8' }}>{pending ? '归档完成后即可播放。' : '可能已过期、网络中断或浏览器不支持该编码。'}</div>
          <div className="flex gap-2">
            <button onClick={() => setAttempt(value => value + 1)}
              className="inline-flex items-center gap-1 rounded border px-2.5 py-1 cursor-pointer"
              style={{ borderColor: '#475569', background: 'rgba(255,255,255,0.08)' }}>
              <RotateCcw size={12} /> 重试
            </button>
            <a href={src} target="_blank" rel="noreferrer" className="rounded border px-2.5 py-1 underline"
              style={{ borderColor: '#475569' }}>打开原视频</a>
          </div>
        </div>
      )}
      {state === 'ready' && muted && (
        <button onClick={() => { setMuted(false); videoRef.current?.play().catch(() => undefined); }}
          className="absolute bottom-3 right-3 inline-flex items-center gap-1 rounded-full px-3 py-1.5 text-xs cursor-pointer"
          style={{ background: 'rgba(15,23,42,0.85)', color: '#e2e8f0', border: '1px solid #475569' }}>
          <Volume2 size={13} /> 开启声音
        </button>
      )}
    </div>
  );
}
