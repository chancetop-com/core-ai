import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { Activity, Bot, Calendar, ChevronRight, Database, Files, Key, ListChecks, MessageCircle, Moon, Network, PanelLeft, Sparkles, Star, Sun, FileText, LogOut, Wrench, Settings, Webhook, Workflow, Zap, Radio } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';
import { useCapabilities } from '../api/capabilities';
import { useAuth } from '../api/auth';
import QuickActionDialog from './QuickActionDialog';

interface NavItem {
  to: string;
  icon?: React.ComponentType<{ size: number; className?: string }>;
  label: string;
  show: boolean;
  children?: { to: string; icon?: React.ComponentType<{ size: number; className?: string }>; label: string; show: boolean }[];
}

export default function Layout() {
  const { dark, toggle } = useTheme();
  const caps = useCapabilities();
  const { user, logout } = useAuth();
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const titles: Record<string, string> = {
      '/chat': 'Chat',
      '/for-you': 'For You',
      '/traces': 'Traces',
      '/agents': 'Agents',
      '/system-prompts': 'System Prompts',
      '/login': 'Login',
      '/scheduler': 'Scheduler',
      '/tasks': 'Tasks',
      '/mcp': 'MCP',
      '/tools': 'Tools',
      '/tools/builtin': 'Built-in Tools',
      '/api-tools': 'API Tools',
      '/skills': 'Skills',
      '/datasets': 'Datasets',
      '/settings': 'Settings',
      '/triggers': 'Triggers',
      '/triggers/webhook': 'Webhook Triggers',
      '/triggers/channels': 'Channels',
      '/triggers/openclaw': 'OpenClaw',
      '/triggers/schedule': 'Scheduler',
    };
    const path = location.pathname;
    const title = titles[path]
      || (path.startsWith('/agents/') ? 'Agent Detail' : null)
      || (path.startsWith('/traces/') ? 'Trace Detail' : null)
      || (path.startsWith('/runs/') ? 'Run Detail' : null)
      || (path.startsWith('/system-prompts/') ? 'System Prompt' : null)
      || (path.startsWith('/api-tools/') ? 'API Tool Detail' : null)
      || (path.startsWith('/datasets/') ? 'Dataset Detail' : null)
      || (path.startsWith('/settings/') ? 'Settings' : null)
      || 'core-ai';
    document.title = `${title} - core-ai`;
  }, [location.pathname]);

  const [expandedNav, setExpandedNav] = useState<string | null>(null);

  const isRouteActive = (to: string, pathname: string): boolean => {
    if (to === '/traces') return pathname === '/traces' || pathname.startsWith('/traces/');
    if (to === '/agents') return pathname === '/agents' || pathname.startsWith('/agents/');
    if (to === '/system-prompts') return pathname === '/system-prompts' || pathname.startsWith('/system-prompts/');
    if (to === '/tools') return pathname === '/tools' || pathname.startsWith('/tools/');
    if (to === '/api-tools') return pathname === '/api-tools' || pathname.startsWith('/api-tools/');
    if (to === '/skills') return pathname === '/skills' || pathname.startsWith('/skills/');
    if (to === '/datasets') return pathname === '/datasets' || pathname.startsWith('/datasets/');
    if (to === '/tasks') return pathname === '/tasks' || pathname.startsWith('/runs/');
    if (to === '/settings') return pathname === '/settings' || pathname.startsWith('/settings/');
    if (to === '/triggers') return pathname === '/triggers/webhook' || pathname === '/triggers/schedule' || pathname === '/triggers/channels' || pathname === '/triggers/openclaw';
    if (to === '/for-you') return pathname === '/for-you' || pathname.startsWith('/for-you/');
    return pathname === to;
  };

  const navItems: NavItem[] = [
    { to: '/for-you', icon: Star, label: 'For You', show: true, children: [
      { to: '/for-you/artifacts', icon: Files, label: 'Artifacts', show: true },
    ]},
    { to: '/chat', icon: MessageCircle, label: 'Chat', show: caps.chat },
    { to: '/traces', icon: Activity, label: 'Traces', show: caps.traces },
    { to: '/agents', icon: Bot, label: 'Agents', show: true },
    { to: '/workflows', icon: Workflow, label: 'Workflows', show: true },
    { to: '/system-prompts', icon: FileText, label: 'System Prompts', show: caps.systemPrompts },
    { to: '/tasks', icon: ListChecks, label: 'Tasks', show: false }, // hidden until Tasks page is built
    { to: '/triggers', icon: Zap, label: 'Triggers', show: true, children: [
      { to: '/triggers/webhook', icon: Webhook, label: 'Webhook', show: true },
      { to: '/triggers/channels', icon: Radio, label: 'Channels', show: true },
      { to: '/triggers/openclaw', icon: Zap, label: 'OpenClaw', show: true },
      { to: '/triggers/schedule', icon: Calendar, label: 'Schedule', show: true },
    ]},
    { to: '/tools', icon: Wrench, label: 'Tools', show: true, children: [
      { to: '/tools/builtin', icon: Wrench, label: 'Builtin Tools', show: true },
      { to: '/mcp', icon: Network, label: 'MCP', show: true },
      { to: '/api-tools', icon: Key, label: 'API Tools', show: true },
    ]},
    { to: '/skills', icon: Sparkles, label: 'Skills', show: true },
    { to: '/datasets', icon: Database, label: 'Datasets', show: true },
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
            <NavLink to="/" className="cursor-pointer flex items-center" aria-label="core-ai">
              <img
                src={dark ? '/logo-lockup-dark.svg' : '/logo-lockup.svg'}
                alt="core-ai"
                className="h-9"
              />
            </NavLink>
          )}
        </div>

        {/* Navigation */}
        <nav className="flex-1 p-2 flex flex-col gap-1 overflow-y-auto">
          {navItems.map(({ to, icon: Icon, label, children }) => {
            const hasChildren = children && children.length > 0;
            const isExpanded = expandedNav === to;
            const anyChildActive = hasChildren && children.some(c => isRouteActive(c.to, location.pathname));
            const selfActive = !hasChildren && isRouteActive(to, location.pathname);
            const active = selfActive || anyChildActive;
            return (
              <div key={to}>
                <div
                  onClick={() => {
                    if (hasChildren) {
                      setExpandedNav(isExpanded ? null : to);
                    }
                    navigate(to);
                  }}
                  role={hasChildren ? 'button' : undefined}
                  className={`flex items-center gap-3 pr-3 py-2 rounded-lg text-sm transition-colors cursor-pointer ${collapsed ? 'justify-center pl-3' : ''}`}
                  style={{
                    paddingLeft: collapsed ? undefined : active ? '9px' : '12px',
                    borderLeft: collapsed ? undefined : active ? '3px solid var(--color-primary)' : '3px solid transparent',
                    background: active ? 'var(--color-primary-bg)' : 'transparent',
                    color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                  }}>
                  {Icon && <Icon size={18} />}
                  {!collapsed && (
                    <>
                      <NavLink to={to} end={to === '/'} className="flex-1"
                        onClick={e => {
                          if (hasChildren) e.preventDefault();
                        }}>
                        {label}
                      </NavLink>
                      {hasChildren && (
                        <ChevronRight size={14}
                          className={`transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`} />
                      )}
                    </>
                  )}
                </div>
                {!collapsed && hasChildren && isExpanded && (
                  <div className="ml-6 mt-1 flex flex-col gap-0.5">
                    {children.filter(c => c.show).map(child => {
                      const ChildIcon = child.icon;
                      const childActive = isRouteActive(child.to, location.pathname);
                      return (
                        <NavLink key={child.to} to={child.to}
                          className="flex items-center gap-2 pr-3 py-1.5 rounded-lg text-sm transition-colors"
                          style={{
                            paddingLeft: childActive ? '17px' : '20px',
                            borderLeft: childActive ? '3px solid var(--color-primary)' : '3px solid transparent',
                            background: childActive ? 'var(--color-primary-bg)' : 'transparent',
                            color: childActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                            fontWeight: childActive ? 500 : 400,
                          }}>
                          {ChildIcon && <ChildIcon size={14} className="opacity-70" />}
                          {child.label}
                        </NavLink>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </nav>

        {/* Footer */}
        <div className="p-2 border-t space-y-1" style={{ borderColor: 'var(--color-border)' }}>
          {user && !collapsed && (
            <div className="px-3 py-1.5 text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>
              {user.name || user.userId}
            </div>
          )}
          <button onClick={() => navigate('/settings')}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer ${collapsed ? 'justify-center' : ''}`}
            style={{ color: 'var(--color-text-secondary)' }}>
            <Settings size={16} />
            {!collapsed && 'Settings'}
          </button>
          <button onClick={toggle}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer ${collapsed ? 'justify-center' : ''}`}
            style={{ color: 'var(--color-text-secondary)' }}>
            {dark ? <Sun size={16} /> : <Moon size={16} />}
            {!collapsed && (dark ? 'Light Mode' : 'Dark Mode')}
          </button>
          <button onClick={logout}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer ${collapsed ? 'justify-center' : ''}`}
            style={{ color: 'var(--color-text-secondary)' }}>
            <LogOut size={16} />
            {!collapsed && 'Sign Out'}
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-auto" style={{ background: 'var(--color-bg)' }}>
        <Outlet />
      </main>
      <QuickActionDialog />
    </div>
  );
}
