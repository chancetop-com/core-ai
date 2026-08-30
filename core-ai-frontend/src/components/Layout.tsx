import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { Activity, Bell, Bot, Brain, Calendar, ChevronRight, Cpu, Database, Files, FlaskConical, FolderKanban, Gauge, Key, ListChecks, MessageCircle, Moon, Network, PanelLeft, Play, RotateCcw, Sparkles, Star, Sun, FileText, LogOut, Wrench, Settings, Webhook, Workflow, Zap, Radio } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';
import { useCapabilities } from '../api/capabilities';
import { useAuth } from '../api/auth';
import { hasPermission } from '../api/permissions';
import QuickActionDialog from './QuickActionDialog';

interface NavItem {
  to: string;
  icon?: React.ComponentType<{ size: number; className?: string }>;
  label: string;
  show: boolean;
  permission?: string;
  children?: { to: string; icon?: React.ComponentType<{ size: number; className?: string }>; label: string; show: boolean; permission?: string }[];
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
      '/observability': 'Observability',
      '/traces': 'Traces',
      '/generations': 'Generations',
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
      '/projects': 'Projects',
      '/settings': 'Settings',
      '/notifications': 'Notifications',
      '/triggers': 'Triggers',
      '/triggers/webhook': 'Webhook Triggers',
      '/triggers/channels': 'Channels',
      '/triggers/openclaw': 'OpenClaw',
      '/triggers/schedule': 'Scheduler',
      '/experiments/memory': 'Agent Memory',
    };
    const path = location.pathname;
    const title = titles[path]
      || (path.startsWith('/agents/') ? 'Agent Detail' : null)
      || (path.startsWith('/skills/marketplace/') ? 'Marketplace Repo' : null)
      || (path.startsWith('/skills/') ? 'Skill Detail' : null)
      || (path.startsWith('/traces/') ? 'Trace Detail' : null)
      || (path.startsWith('/runs/') ? 'Run Detail' : null)
      || (path.startsWith('/system-prompts/') ? 'System Prompt' : null)
      || (path.startsWith('/api-tools/') ? 'API Tool Detail' : null)
      || (path.startsWith('/datasets/') ? 'Dataset Detail' : null)
      || (path.startsWith('/projects/') && path.endsWith('/playbook') ? 'Playbook' : null)
      || (path.startsWith('/projects/') && path.includes('/subjects/') ? 'Subject Detail' : null)
      || (path.startsWith('/projects/') ? 'Project Detail' : null)
      || (path.startsWith('/settings/') ? 'Settings' : null)
      || 'core-ai';
    document.title = `${title} - core-ai`;
  }, [location.pathname]);

  const [expandedNav, setExpandedNav] = useState<string | null>(null);

  const isRouteActive = (to: string, pathname: string): boolean => {
    if (to === '/traces') return pathname === '/traces' || pathname.startsWith('/traces/');
    if (to === '/observability') return pathname === '/traces' || pathname.startsWith('/traces/') || pathname === '/generations' || pathname.startsWith('/generations/');
    if (to === '/agents') return pathname === '/agents' || pathname.startsWith('/agents/');
    if (to === '/system-prompts') return pathname === '/system-prompts' || pathname.startsWith('/system-prompts/');
    if (to === '/tools') return pathname === '/tools' || pathname.startsWith('/tools/');
    if (to === '/api-tools') return pathname === '/api-tools' || pathname.startsWith('/api-tools/');
    if (to === '/skills') return pathname === '/skills' || pathname.startsWith('/skills/');
    if (to === '/datasets') return pathname === '/datasets' || pathname.startsWith('/datasets/');
    if (to === '/projects') return pathname === '/projects' || pathname.startsWith('/projects/');
    if (to === '/tasks') return pathname === '/tasks' || pathname.startsWith('/runs/');
    if (to === '/settings') return pathname === '/settings' || pathname.startsWith('/settings/');
    if (to === '/triggers') return pathname === '/triggers/webhook' || pathname === '/triggers/schedule' || pathname === '/triggers/channels' || pathname === '/triggers/openclaw';
    if (to === '/for-you') return pathname === '/for-you' || pathname.startsWith('/for-you/');
    if (to === '/experiments') return pathname === '/experiments' || pathname.startsWith('/experiments/');
    return pathname === to;
  };

  const navItems: NavItem[] = [
    { to: '/for-you', icon: Star, label: 'For You', show: true, permission: 'dashboard.view', children: [
      { to: '/for-you/artifacts', icon: Files, label: 'Artifacts', show: true, permission: 'dashboard.view' },
    ]},
    { to: '/chat', icon: MessageCircle, label: 'Chat', show: caps.chat, permission: 'chat.use' },
    { to: '/projects', icon: FolderKanban, label: 'Projects', show: true, permission: 'project.view' },
    { to: '/observability', icon: Gauge, label: 'Observability', show: caps.traces, permission: 'trace.view', children: [
      { to: '/traces', icon: Activity, label: 'Traces', show: caps.traces, permission: 'trace.view' },
      { to: '/generations', icon: Cpu, label: 'Generations', show: caps.traces, permission: 'trace.view' },
    ]},
    { to: '/agents', icon: Bot, label: 'Agents', show: true, permission: 'agent.view' },
    { to: '/workflows', icon: Workflow, label: 'Workflows', show: true, permission: 'workflow.view' },
    { to: '/system-prompts', icon: FileText, label: 'System Prompts', show: caps.systemPrompts, permission: 'prompt.view' },
    { to: '/tasks', icon: ListChecks, label: 'Tasks', show: false, permission: 'task.view' }, // hidden until Tasks page is built
    { to: '/triggers', icon: Zap, label: 'Triggers', show: true, permission: 'trigger.view', children: [
      { to: '/triggers/webhook', icon: Webhook, label: 'Webhook', show: true, permission: 'trigger.view' },
      { to: '/triggers/channels', icon: Radio, label: 'Channels', show: true, permission: 'trigger.view' },
      { to: '/triggers/openclaw', icon: Zap, label: 'OpenClaw', show: true, permission: 'trigger.view' },
      { to: '/triggers/schedule', icon: Calendar, label: 'Schedule', show: true, permission: 'trigger.view' },
    ]},
    { to: '/tools', icon: Wrench, label: 'Tools', show: true, permission: 'tool.view', children: [
      { to: '/tools/builtin', icon: Wrench, label: 'Builtin Tools', show: true, permission: 'tool.view' },
      { to: '/mcp', icon: Network, label: 'MCP', show: true, permission: 'mcp.view' },
      { to: '/api-tools', icon: Key, label: 'API Tools', show: true, permission: 'apitool.view' },
    ]},
    { to: '/skills', icon: Sparkles, label: 'Skills', show: true, permission: 'skill.view' },
    { to: '/datasets', icon: Database, label: 'Datasets', show: true, permission: 'dataset.view' },
    { to: '/experiments', icon: FlaskConical, label: 'Experiments', show: true, permission: 'experiment.view', children: [
      { to: '/experiments/playground', icon: Play, label: 'Playground', show: true, permission: 'experiment.view' },
      { to: '/experiments/replay', icon: RotateCcw, label: 'Replay Debug', show: true, permission: 'experiment.view' },
      { to: '/experiments/memory', icon: Brain, label: 'Agent Memory', show: true, permission: 'experiment.view' },
    ]},
  ].filter(item => item.show && (!item.permission || hasPermission(user?.permissions, item.permission)));

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
            const anyChildActive = hasChildren && children.some(c => isRouteActive(c.to, location.pathname));
            const selfActive = !hasChildren && isRouteActive(to, location.pathname);
            const active = selfActive || anyChildActive;
            // Auto-expand when landing directly on a child route (e.g. /experiments/replay); once the
            // user manually toggles a group, expandedNav takes over.
            const isExpanded = expandedNav === to || (expandedNav === null && anyChildActive);
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
                    {children.filter(c => c.show && (!c.permission || hasPermission(user?.permissions, c.permission))).map(child => {
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
          <button onClick={() => navigate('/notifications')}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer ${collapsed ? 'justify-center' : ''}`}
            style={{ color: 'var(--color-text-secondary)' }}>
            <Bell size={16} />
            {!collapsed && 'Notifications'}
          </button>
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
