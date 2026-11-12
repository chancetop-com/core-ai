// Voice capture for the composer. Records mono PCM through Web Audio, resamples to 16 kHz and
// encodes a plain WAV so every server-side provider (OpenAI whisper/transcribe, Azure Speech)
// accepts the clip without a container/codec negotiation.

export interface VoiceClip {
  bytes: Uint8Array;
  mimeType: string;
  durationMs: number;
}

export interface VoiceRecorderOptions {
  maxMs?: number;
  onTick?: (elapsedMs: number) => void;
  onAutoStop?: () => void;
  onLevel?: (level: number) => void;
}

const TARGET_SAMPLE_RATE = 16_000;
const DEFAULT_MAX_MS = 5 * 60 * 1000;

export class VoiceRecorder {
  private readonly options: VoiceRecorderOptions;
  private stream: MediaStream | null = null;
  private context: AudioContext | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private processor: ScriptProcessorNode | null = null;
  private silentGain: GainNode | null = null;
  private chunks: Float32Array[] = [];
  private sampleRate = 48_000;
  private startedAt = 0;
  private ticker: ReturnType<typeof setInterval> | null = null;
  private stopped = false;

  constructor(options: VoiceRecorderOptions = {}) {
    this.options = options;
  }

  get recording(): boolean {
    return this.context !== null && !this.stopped;
  }

  async start(): Promise<void> {
    if (this.recording) return;
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error('microphone capture is not available in this build');
    }
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    const context = new AudioContext();
    await context.resume();
    const source = context.createMediaStreamSource(stream);
    const processor = context.createScriptProcessor(4096, 1, 1);
    // ScriptProcessor only runs while connected; route it into a muted gain so the mic is not played back.
    const silentGain = context.createGain();
    silentGain.gain.value = 0;
    processor.onaudioprocess = (event) => {
      if (this.stopped) return;
      const samples = new Float32Array(event.inputBuffer.getChannelData(0));
      this.chunks.push(samples);
      this.options.onLevel?.(rms(samples));
    };
    source.connect(processor);
    processor.connect(silentGain);
    silentGain.connect(context.destination);

    this.stream = stream;
    this.context = context;
    this.source = source;
    this.processor = processor;
    this.silentGain = silentGain;
    this.sampleRate = context.sampleRate;
    this.chunks = [];
    this.stopped = false;
    this.startedAt = Date.now();
    const maxMs = this.options.maxMs ?? DEFAULT_MAX_MS;
    this.ticker = setInterval(() => {
      const elapsed = Date.now() - this.startedAt;
      this.options.onTick?.(elapsed);
      if (elapsed >= maxMs) {
        this.options.onAutoStop?.();
      }
    }, 250);
  }

  async stop(): Promise<VoiceClip> {
    if (!this.recording) throw new Error('recording has not started');
    const durationMs = Date.now() - this.startedAt;
    const sampleRate = this.sampleRate;
    this.stopped = true;
    this.teardown();
    const pcm = concat(this.chunks);
    this.chunks = [];
    if (pcm.length === 0) throw new Error('no audio was captured');
    const resampled = sampleRate === TARGET_SAMPLE_RATE ? pcm : await resample(pcm, sampleRate, TARGET_SAMPLE_RATE);
    return { bytes: encodeWav(resampled, TARGET_SAMPLE_RATE), mimeType: 'audio/wav', durationMs };
  }

  cancel(): void {
    this.stopped = true;
    this.teardown();
    this.chunks = [];
  }

  private teardown(): void {
    if (this.ticker !== null) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
    if (this.processor) {
      this.processor.onaudioprocess = null;
      this.processor.disconnect();
      this.processor = null;
    }
    if (this.source) {
      this.source.disconnect();
      this.source = null;
    }
    if (this.silentGain) {
      this.silentGain.disconnect();
      this.silentGain = null;
    }
    if (this.stream) {
      for (const track of this.stream.getTracks()) track.stop();
      this.stream = null;
    }
    if (this.context) {
      void this.context.close().catch(() => undefined);
      this.context = null;
    }
  }
}

export function encodeWav(samples: Float32Array, sampleRate: number): Uint8Array {
  const dataBytes = samples.length * 2;
  const buffer = new ArrayBuffer(44 + dataBytes);
  const view = new DataView(buffer);
  writeText(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataBytes, true);
  writeText(view, 8, 'WAVE');
  writeText(view, 12, 'fmt ');
  view.setUint32(16, 16, true);          // PCM chunk size
  view.setUint16(20, 1, true);           // PCM
  view.setUint16(22, 1, true);           // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true);           // block align
  view.setUint16(34, 16, true);          // bits per sample
  writeText(view, 36, 'data');
  view.setUint32(40, dataBytes, true);
  let offset = 44;
  for (let i = 0; i < samples.length; i += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(offset, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
    offset += 2;
  }
  return new Uint8Array(buffer);
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function writeText(view: DataView, offset: number, text: string): void {
  for (let i = 0; i < text.length; i += 1) view.setUint8(offset + i, text.charCodeAt(i));
}

function rms(samples: Float32Array): number {
  let sum = 0;
  for (let i = 0; i < samples.length; i += 1) sum += samples[i] * samples[i];
  return Math.min(1, Math.sqrt(sum / samples.length) * 4);
}

function concat(chunks: Float32Array[]): Float32Array {
  let length = 0;
  for (const chunk of chunks) length += chunk.length;
  const result = new Float32Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

async function resample(samples: Float32Array, from: number, to: number): Promise<Float32Array> {
  const frames = Math.max(1, Math.ceil(samples.length * to / from));
  const offline = new OfflineAudioContext(1, frames, to);
  const buffer = offline.createBuffer(1, samples.length, from);
  buffer.getChannelData(0).set(samples);
  const source = offline.createBufferSource();
  source.buffer = buffer;
  source.connect(offline.destination);
  source.start();
  const rendered = await offline.startRendering();
  return rendered.getChannelData(0);
}
