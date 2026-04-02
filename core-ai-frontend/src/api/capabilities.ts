import { createContext, useContext } from 'react';

export interface Capabilities {
  chat: boolean;
  traces: boolean;
  prompts: boolean;
  dashboard: boolean;
  systemPrompts: boolean;
  authRequired: boolean;
}

export const defaultCapabilities: Capabilities = {
  chat: true,
  traces: true,
  prompts: true,
  dashboard: true,
  systemPrompts: true,
  authRequired: false,
};

export const CapabilitiesContext = createContext<Capabilities>(defaultCapabilities);

export function useCapabilities() {
  return useContext(CapabilitiesContext);
}

export async function fetchCapabilities(): Promise<Capabilities> {
  try {
    const res = await fetch('/api/capabilities');
    if (!res.ok) return defaultCapabilities;
    const caps = await res.json();
    return { ...defaultCapabilities, ...caps, systemPrompts: caps.systemPrompts ?? caps.prompts ?? false };
  } catch {
    return defaultCapabilities;
  }
}
