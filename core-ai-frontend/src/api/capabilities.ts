import { createContext, useContext } from 'react';

export interface Capabilities {
  chat: boolean;
  traces: boolean;
  prompts: boolean;
  dashboard: boolean;
}

export const defaultCapabilities: Capabilities = {
  chat: false,
  traces: false,
  prompts: false,
  dashboard: false,
};

export const CapabilitiesContext = createContext<Capabilities>(defaultCapabilities);

export function useCapabilities() {
  return useContext(CapabilitiesContext);
}

export async function fetchCapabilities(): Promise<Capabilities> {
  try {
    const res = await fetch('/api/capabilities');
    if (!res.ok) return defaultCapabilities;
    return await res.json();
  } catch {
    return defaultCapabilities;
  }
}
