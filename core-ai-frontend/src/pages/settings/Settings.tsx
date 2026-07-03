import { Activity, PlugZap, Shield, User } from 'lucide-react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../../api/auth';

export default function Settings() {
  const { user } = useAuth();

  return (
    <div className="flex h-full">
      {/* Settings Sidebar */}
      <div className="w-56 border-r flex flex-col" style={{ borderColor: 'var(--color-border)' }}>
        <div className="p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold text-lg">Settings</h2>
        </div>
        <nav className="flex-1 p-2 flex flex-col gap-1">
          <NavLink to="/settings/api-keys"
            className={() =>
              'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors'
            }
            style={({ isActive }) => ({
              background: isActive ? 'var(--color-primary-bg)' : 'transparent',
              color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              fontWeight: isActive ? 500 : 400,
            })}>
            <Shield size={16} />
            Security
          </NavLink>
          {user?.role === 'admin' && (
            <>
              <NavLink to="/settings"
                end
                className={() =>
                  'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors'
                }
                style={({ isActive }) => ({
                  background: isActive ? 'var(--color-primary-bg)' : 'transparent',
                  color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                  fontWeight: isActive ? 500 : 400,
                })}>
                <Activity size={16} />
                Dashboard
              </NavLink>
              <NavLink to="/settings/users"
                className={() =>
                  'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors'
                }
                style={({ isActive }) => ({
                  background: isActive ? 'var(--color-primary-bg)' : 'transparent',
                  color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                  fontWeight: isActive ? 500 : 400,
                })}>
                <User size={16} />
                User Management
              </NavLink>
              <NavLink to="/settings/gateway"
                className={() =>
                  'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors'
                }
                style={({ isActive }) => ({
                  background: isActive ? 'var(--color-primary-bg)' : 'transparent',
                  color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                  fontWeight: isActive ? 500 : 400,
                })}>
                <PlugZap size={16} />
                Gateway
              </NavLink>
            </>
          )}
        </nav>
      </div>
      {/* Settings Content */}
      <div className="flex-1 overflow-auto">
        <Outlet />
      </div>
    </div>
  );
}
