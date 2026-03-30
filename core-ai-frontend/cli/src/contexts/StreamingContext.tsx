import { createContext, useContext } from 'react';
import type { StreamingState } from '../types.js';

export const StreamingContext = createContext<StreamingState>('idle');
export const useStreamingState = () => useContext(StreamingContext);
