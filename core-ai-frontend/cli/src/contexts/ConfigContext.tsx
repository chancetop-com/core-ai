import { createContext, useContext } from 'react';
import type { A2AClient } from '../client.js';

export interface AppConfig {
  baseUrl: string;
  client: A2AClient;
  modelName: string;
  agentName: string;
  skipPermissions: boolean;
}

export const ConfigContext = createContext<AppConfig>(null!);
export const useConfig = () => useContext(ConfigContext);
