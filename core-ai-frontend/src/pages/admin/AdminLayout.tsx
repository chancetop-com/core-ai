import { Activity, Bell, ListChecks, Settings, User } from 'lucide-react';
import { Navigate } from 'react-router-dom';
import SectionLayout, { type SectionNavItem } from '../../components/SectionLayout';
import { useAuth } from '../../api/auth';
import { getSystemSettingsAccess, hasPermission } from '../../api/permissions';

export function AdminHome() {
  const { user } = useAuth();
  const { canManageSystem, canManageSnapshots } = getSystemSettingsAccess(user);

  if (user?.role === 'admin') return <Navigate to="/admin/dashboard" replace />;
  if (canManageSystem || canManageSnapshots) return <Navigate to="/admin/system" replace />;
  if (hasPermission(user?.permissions, 'user.manage')) return <Navigate to="/admin/users" replace />;
  if (hasPermission(user?.permissions, 'task.view')) return <Navigate to="/admin/tasks" replace />;
  if (hasPermission(user?.permissions, 'costalert.manage')) return <Navigate to="/admin/cost-alerts" replace />;
  return <Navigate to="/for-you" replace />;
}

export default function AdminLayout() {
  const { user } = useAuth();
  const { canManageSystem, canManageSnapshots } = getSystemSettingsAccess(user);

  const items: SectionNavItem[] = [
    { to: '/admin/dashboard', end: true, icon: Activity, label: 'Dashboard', show: user?.role === 'admin' },
    { to: '/admin/users', icon: User, label: 'User Management', show: hasPermission(user?.permissions, 'user.manage') },
    { to: '/admin/tasks', icon: ListChecks, label: 'System Tasks', show: hasPermission(user?.permissions, 'task.view') },
    { to: '/admin/system', icon: Settings, label: 'System Configuration', show: canManageSystem || canManageSnapshots },
    { to: '/admin/cost-alerts', icon: Bell, label: 'Cost Alerts', show: hasPermission(user?.permissions, 'costalert.manage') },
  ];

  return <SectionLayout title="Admin" items={items} />;
}
