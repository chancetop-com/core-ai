import { User } from 'lucide-react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../../api/auth';

export default function Settings() {
  const { user } = useAuth();
  const location = useLocation();
  const hasSubRoute = location.pathname !== '/settings';

  return (
    <div className="flex h-full">
      {/* Settings Sidebar */}
      <div className="w-56 border-r flex flex-col" style={{ borderColor: 'var(--color-border)' }}>
        <div className="p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold text-lg">Settings</h2>
        </div>
        <nav className="flex-1 p-2 flex flex-col gap-1">
          {user?.role === 'admin' && (
            <NavLink to="/settings/users"
              className={() =>
                'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors'
              }
              style={() => ({
                background: 'transparent',
                color: 'var(--color-text-secondary)',
              })}>
              <User size={16} />
              User Management
            </NavLink>
          )}
        </nav>
      </div>
      {/* Settings Content */}
      <div className="flex-1 overflow-auto">
        {hasSubRoute ? <Outlet /> : (
          <div className="p-6">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Select a settings category from the sidebar.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}