import { NavLink, Outlet } from 'react-router-dom';
import { useState } from 'react';
import { Activity, BookText, LayoutDashboard, MessageCircle, Moon, Sun, PanelLeft } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';
import { useCapabilities } from '../api/capabilities';

export default function Layout() {
  const { dark, toggle } = useTheme();
  const caps = useCapabilities();
  const [collapsed, setCollapsed] = useState(false);

  const navItems = [
    { to: '/chat', icon: MessageCircle, label: 'Chat', show: caps.chat },
    { to: '/', icon: Activity, label: 'Traces', show: caps.traces },
    { to: '/prompts', icon: BookText, label: 'Prompts', show: caps.prompts },
    { to: '/dashboard', icon: LayoutDashboard, label: 'Dashboard', show: caps.dashboard },
  ].filter(item => item.show);

  return (
    <div className="flex h-screen">
      <aside className="flex flex-col border-r transition-all duration-200"
        style={{ 
          width: collapsed ? '60px' : '224px',
          background: 'var(--color-bg-secondary)', 
          borderColor: 'var(--color-border)' 
        }}>
        {/* Header */}
        <div className="p-3 flex items-center gap-2 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <button
            onClick={() => setCollapsed(!collapsed)}
            className="w-9 h-9 flex items-center justify-center rounded-lg cursor-pointer transition-colors shrink-0"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}
            title={collapsed ? 'Expand' : 'Collapse'}>
            <PanelLeft size={18} />
          </button>
          {!collapsed && (
            <span className="font-semibold text-lg">Core AI</span>
          )}
        </div>

        {/* Navigation */}
        <nav className="flex-1 p-2 flex flex-col gap-1 overflow-y-auto">
          {navItems.map(({ to, icon: Icon, label }) => (
            <NavLink key={to} to={to} end={to === '/' || to === '/chat'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors ${isActive ? 'font-medium' : ''} ${collapsed ? 'justify-center' : ''}`
              }
              style={({ isActive }) => ({
                background: isActive ? 'var(--color-bg-tertiary)' : 'transparent',
                color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              })}>
              <Icon size={18} />
              {!collapsed && label}
            </NavLink>
          ))}
        </nav>

        {/* Theme toggle */}
        <div className="p-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <button onClick={toggle}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer ${collapsed ? 'justify-center' : ''}`}
            style={{ color: 'var(--color-text-secondary)' }}>
            {dark ? <Sun size={16} /> : <Moon size={16} />}
            {!collapsed && (dark ? 'Light Mode' : 'Dark Mode')}
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-auto" style={{ background: 'var(--color-bg)' }}>
        <Outlet />
      </main>
    </div>
  );
}
