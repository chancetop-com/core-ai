function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  return headers;
}

export interface SpeechTokenResponse {
  token: string;
  region: string;
}

export async function fetchSpeechToken(): Promise<SpeechTokenResponse> {
  const res = await fetch('/api/speech/token', {
    headers: getAuthHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Speech token fetch failed: ${res.status}`);
  }
  return res.json();
}
