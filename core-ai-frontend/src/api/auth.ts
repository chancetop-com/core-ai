import { createContext, useContext } from 'react';

export interface AuthUser {
  apiKey: string;
  userId: string;
  name: string;
  role?: string;
  permissions?: string[];
}

export const AuthContext = createContext<{
  user: AuthUser | null;
  login: (apiKey: string, userId: string, name: string, role?: string, permissions?: string[]) => void;
  logout: () => void;
}>({ user: null, login: () => {}, logout: () => {} });

export function useAuth() {
  return useContext(AuthContext);
}

const PERMISSIONS_KEY = 'userPermissions';

export function getStoredUser(): AuthUser | null {
  const apiKey = localStorage.getItem('apiKey');
  const userId = localStorage.getItem('userId');
  const name = localStorage.getItem('userName');
  const role = localStorage.getItem('userRole');
  // Don't return local user in server mode - 'local' is only valid in CLI mode
  if (apiKey && userId && apiKey !== 'local') {
    return {
      apiKey,
      userId,
      name: name || userId,
      role: role || undefined,
      permissions: getStoredPermissions(),
    };
  }
  return null;
}

export function storeUser(apiKey: string, userId: string, name: string, role?: string, permissions?: string[]) {
  localStorage.setItem('apiKey', apiKey);
  localStorage.setItem('userId', userId);
  localStorage.setItem('userName', name);
  if (role) localStorage.setItem('userRole', role);
  else localStorage.removeItem('userRole');
  storePermissions(permissions);
}

export function clearUser() {
  localStorage.removeItem('apiKey');
  localStorage.removeItem('userId');
  localStorage.removeItem('userName');
  localStorage.removeItem('userRole');
  localStorage.removeItem(PERMISSIONS_KEY);
}

export function storePermissions(permissions?: string[]) {
  if (permissions && permissions.length > 0) localStorage.setItem(PERMISSIONS_KEY, JSON.stringify(permissions));
  else localStorage.removeItem(PERMISSIONS_KEY);
}

export function getStoredPermissions(): string[] | undefined {
  const raw = localStorage.getItem(PERMISSIONS_KEY);
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}
