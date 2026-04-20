import { createContext, useContext } from 'react';

export interface AuthUser {
  apiKey: string;
  userId: string;
  name: string;
  role?: string;
}

export const AuthContext = createContext<{
  user: AuthUser | null;
  login: (apiKey: string, userId: string, name: string, role?: string) => void;
  logout: () => void;
}>({ user: null, login: () => {}, logout: () => {} });

export function useAuth() {
  return useContext(AuthContext);
}

export function getStoredUser(): AuthUser | null {
  const apiKey = localStorage.getItem('apiKey');
  const userId = localStorage.getItem('userId');
  const name = localStorage.getItem('userName');
  const role = localStorage.getItem('userRole');
  // Don't return local user in server mode - 'local' is only valid in CLI mode
  if (apiKey && userId && apiKey !== 'local') {
    return { apiKey, userId, name: name || userId, role: role || undefined };
  }
  return null;
}

export function storeUser(apiKey: string, userId: string, name: string, role?: string) {
  localStorage.setItem('apiKey', apiKey);
  localStorage.setItem('userId', userId);
  localStorage.setItem('userName', name);
  if (role) localStorage.setItem('userRole', role);
  else localStorage.removeItem('userRole');
}

export function clearUser() {
  localStorage.removeItem('apiKey');
  localStorage.removeItem('userId');
  localStorage.removeItem('userName');
  localStorage.removeItem('userRole');
}
