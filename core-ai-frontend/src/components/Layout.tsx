import { NavLink, Outlet } from 'react-router-dom';
import { Activity, BookText, LayoutDashboard, Moon, Sun } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';

const navItems = [
  { to: '/', icon: Activity, label: 'Traces' },
  { to: '/prompts', icon: BookText, label: 'Prompts' },
  { to: '/dashboard', icon: LayoutDashboard, label: 'Dashboard' },
];

export default function Layout() {
  const { dark, toggle } = useTheme();

  return (
    <div className="flex h-screen">
      <aside className="w-56 flex flex-col border-r"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="p-4 flex items-center gap-2 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <Activity size={22} style={{ color: 'var(--color-primary)' }} />
          <span className="font-semibold text-lg">Core AI Trace</span>
        </div>
        <nav className="flex-1 p-2 flex flex-col gap-1">
          {navItems.map(({ to, icon: Icon, label }) => (
            <NavLink key={to} to={to} end={to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors ${isActive ? 'font-medium' : ''}`
              }
              style={({ isActive }) => ({
                background: isActive ? 'var(--color-bg-tertiary)' : 'transparent',
                color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              })}>
              <Icon size={18} />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="p-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <button onClick={toggle}
            className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer"
            style={{ color: 'var(--color-text-secondary)' }}>
            {dark ? <Sun size={16} /> : <Moon size={16} />}
            {dark ? 'Light Mode' : 'Dark Mode'}
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-auto" style={{ background: 'var(--color-bg)' }}>
        <Outlet />
      </main>
    </div>
  );
}
