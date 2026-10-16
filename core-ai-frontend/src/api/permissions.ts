import { useAuth, type AuthUser } from './auth';

/**
 * Permission helpers mirroring the server-side RBAC rules:
 * - '*' (admin) grants everything
 * - 'xxx.manage' implies 'xxx.view'
 */
export function hasPermission(permissions: string[] | undefined, permission: string): boolean {
  if (!permissions) return false;
  if (permissions.includes('*') || permissions.includes(permission)) return true;
  if (permission.endsWith('.view')) {
    const manage = `${permission.substring(0, permission.length - '.view'.length)}.manage`;
    return permissions.includes(manage);
  }
  return false;
}

export function usePermission(permission: string): boolean {
  const { user } = useAuth();
  return hasPermission(user?.permissions, permission);
}

export function usePermissions(): string[] {
  const { user } = useAuth();
  return user?.permissions ?? [];
}

export function getSystemSettingsAccess(user: AuthUser | null) {
  return {
    canManageSystem: user?.role === 'admin' && hasPermission(user.permissions, 'system.manage'),
    canManageSnapshots: hasPermission(user?.permissions, 'sandbox.snapshot.manage'),
  };
}
