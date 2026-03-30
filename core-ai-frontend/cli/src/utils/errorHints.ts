export function getErrorHint(message: string): string | null {
  if (message.includes('401')) return 'API key is invalid or expired';
  if (message.includes('402')) return 'Quota exhausted';
  if (message.includes('403')) return 'No permission for this model';
  if (message.includes('404')) return 'Model not found';
  if (message.includes('429')) return 'Rate limited, try again later';
  if (message.includes('500')) return 'API server error';
  if (message.includes('503')) return 'Service temporarily unavailable';
  if (/timeout/i.test(message)) return 'Network timeout';
  if (/connection refused/i.test(message)) return 'Cannot connect to API';
  return null;
}
