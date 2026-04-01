import { createContext, useContext } from 'react';

export interface AuthUser {
  apiKey: string;
  userId: string;
  name: string;
}

export const AuthContext = createContext<{
  user: AuthUser | null;
  login: (apiKey: string, userId: string, name: string) => void;
  logout: () => void;
}>({ user: null, login: () => {}, logout: () => {} });

export function useAuth() {
  return useContext(AuthContext);
}

export function getStoredUser(): AuthUser | null {
  const apiKey = localStorage.getItem('apiKey');
  const userId = localStorage.getItem('userId');
  const name = localStorage.getItem('userName');
  if (apiKey && userId) return { apiKey, userId, name: name || userId };
  return null;
}

export function storeUser(apiKey: string, userId: string, name: string) {
  localStorage.setItem('apiKey', apiKey);
  localStorage.setItem('userId', userId);
  localStorage.setItem('userName', name);
}

export function clearUser() {
  localStorage.removeItem('apiKey');
  localStorage.removeItem('userId');
  localStorage.removeItem('userName');
}
