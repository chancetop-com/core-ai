import { Navigate } from 'react-router-dom';
import { usePermission } from '../api/permissions';

interface RequirePermissionProps {
  permission: string;
  children: React.ReactNode;
  fallback?: string;
}

/** Route guard: redirects to fallback when the current user lacks the RBAC permission. */
export default function RequirePermission({ permission, children, fallback = '/for-you' }: RequirePermissionProps) {
  const allowed = usePermission(permission);
  if (!allowed) return <Navigate to={fallback} replace />;
  return <>{children}</>;
}
