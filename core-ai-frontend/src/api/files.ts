const BASE = '';

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: getAuthHeaders(),
    ...options,
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

async function publicRequest<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, options);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

export interface FileShareResponse {
  token: string;
  share_url: string;
}

export interface SharedArtifactResponse {
  file_name: string;
  content_type?: string;
  size: number;
  created_at: string;
  download_url: string;
}

export const fileApi = {
  share: (id: string) =>
    request<FileShareResponse>(`/api/files/${encodeURIComponent(id)}/share`, { method: 'POST' }),
};

export const publicArtifactApi = {
  get: (token: string) =>
    publicRequest<SharedArtifactResponse>(`/api/public/artifacts/${encodeURIComponent(token)}`),

  contentUrl: (token: string) =>
    `/api/public/artifacts/${encodeURIComponent(token)}/content`,
};
