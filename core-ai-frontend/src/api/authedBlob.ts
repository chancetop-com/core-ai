// Bearer-authenticated blob loading for platform file URLs.
// /api/files/{id}/content 307-redirects to a cross-origin signed URL, so anything that has to
// read pixels (canvas drawImage/toBlob) must go through fetch -> object URL instead of an <img>.

export function needsAuthFetch(url: string): boolean {
  // Anything pointing at /api/files/{id}/content on this backend needs the bearer token
  // — absolute (https://host/api/...) or relative (/api/...) both match.
  return /\/api\/files\/[^/?#]+\/content/.test(url);
}

export async function fetchBlob(url: string): Promise<Blob> {
  const apiKey = localStorage.getItem('apiKey');
  const headers: Record<string, string> = {};
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.blob();
}

export async function fetchAuthedBlobUrl(url: string): Promise<string> {
  return URL.createObjectURL(await fetchBlob(url));
}

export function fileIdOf(url?: string): string | null {
  const match = url ? /\/api\/files\/([^/?#]+)\/content/.exec(url) : null;
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}
