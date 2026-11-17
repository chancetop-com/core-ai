// GET {base}/models on a direct provider. It is the cheap connectivity check behind "Test connection"
// and "Fetch models": no chat completion, so testing a key never spends tokens.
export interface ProbeResult {
  ok: boolean;
  status: number;
  latencyMs: number;
  models: string[];
  error?: string;
}

export async function probeModels(baseUrl: string, apiKey?: string | null, timeoutMs = 8000): Promise<ProbeResult> {
  const started = Date.now();
  const base = String(baseUrl ?? '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(base)) {
    return { ok: false, status: 0, latencyMs: 0, models: [], error: 'base URL must start with http:// or https://' };
  }
  if (base.toLowerCase().includes('/chat/completions')) {
    return { ok: false, status: 0, latencyMs: 0, models: [], error: 'base URL should be the API root, not the completions path' };
  }
  try {
    const response = await fetch(`${base}/models`, {
      headers: apiKey ? { Authorization: `Bearer ${apiKey}` } : {},
      signal: AbortSignal.timeout(timeoutMs),
    });
    const latencyMs = Date.now() - started;
    const text = await response.text();
    if (!response.ok) {
      return { ok: false, status: response.status, latencyMs, models: [], error: summarize(text, response.statusText) };
    }
    try {
      const parsed = JSON.parse(text) as { data?: Array<{ id?: unknown }>; models?: Array<{ id?: unknown }> };
      const entries = Array.isArray(parsed.data) ? parsed.data : Array.isArray(parsed.models) ? parsed.models : [];
      const models = entries.map((entry) => entry?.id).filter((id): id is string => typeof id === 'string' && id.length > 0);
      return { ok: true, status: response.status, latencyMs, models };
    } catch {
      return { ok: true, status: response.status, latencyMs, models: [], error: 'endpoint responded but /models was not JSON; enter models manually' };
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { ok: false, status: 0, latencyMs: Date.now() - started, models: [], error: message };
  }
}

function summarize(text: string, fallback: string): string {
  const body = String(text ?? '').replace(/\s+/g, ' ').trim();
  return (body || fallback || 'request failed').slice(0, 300);
}
