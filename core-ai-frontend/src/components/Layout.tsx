import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { Activity, Bell, Brain, ChevronRight, Cpu, Download, Files, FlaskConical, FolderKanban, Gauge, GitCompare, Library, LogOut, MessageCircle, Moon, PanelLeft, Play, PlugZap, RotateCcw, ShieldCheck, Star, Sun, Terminal, Settings } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';
import { useCapabilities } from '../api/capabilities';
import { useAuth } from '../api/auth';
import { getSystemSettingsAccess, hasPermission } from '../api/permissions';
import QuickActionDialog from './QuickActionDialog';

interface NavItem {
  to: string;
  icon?: React.ComponentType<{ size: number; className?: string }>;
  label: string;
  show: boolean;
  permission?: string;
  children?: { to: string; icon?: React.ComponentType<{ size: number; className?: string }>; label: string; show: boolean; permission?: string }[];
}

const REGISTRY_PATHS = ['/agents', '/workflows', '/system-prompts', '/triggers', '/mcp', '/api-tools', '/skills', '/plugins', '/datasets', '/reports', '/quality', '/runs'];
const REGISTRY_PERMISSIONS = ['agent.view', 'workflow.view', 'prompt.view', 'trigger.view', 'mcp.view', 'apitool.view', 'skill.view', 'plugin.view', 'dataset.view', 'agent.quality.manage'];

function isRegistryPath(pathname: string): boolean {
  return REGISTRY_PATHS.some(path => pathname === path || pathname.startsWith(`${path}/`));
}

export default function Layout() {
  const { dark, toggle } = useTheme();
  const caps = useCapabilities();
  const { user, logout } = useAuth();
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const { canManageSystem, canManageSnapshots } = getSystemSettingsAccess(user);

  useEffect(() => {
    const titles: Record<string, string> = {
      '/chat': 'Chat',
      '/for-you': 'For You',
      '/projects': 'Projects',
      '/observability': 'Observation',
      '/traces': 'Traces',
      '/generations': 'Generations',
      '/calls': 'Hub Calls',
      '/registry': 'Registry',
      '/quality': 'Quality',
      '/agents': 'Agents',
      '/system-prompts': 'System Prompts',
      '/login': 'Login',
      '/scheduler': 'Scheduler',
      '/tasks': 'Tasks',
      '/mcp': 'MCP',
      '/api-tools': 'API Tools',
      '/skills': 'Skills',
      '/plugins': 'Plugins',
      '/datasets': 'Datasets',
      '/datasets/reports': 'Reports',
      '/gateway': 'Gateway',
      '/admin': 'Admin',
      '/admin/dashboard': 'Admin Dashboard',
      '/admin/users': 'User Management',
      '/admin/tasks': 'System Tasks',
      '/admin/system': 'System Configuration',
      '/admin/cost-alerts': 'Cost Alerts',
      '/settings': 'Settings',
      '/notifications': 'Notifications',
      '/triggers': 'Triggers',
      '/triggers/webhook': 'Webhook Triggers',
      '/triggers/channels': 'Channels',
      '/triggers/openclaw': 'OpenClaw',
      '/triggers/schedule': 'Scheduler',
      '/experiments/memory': 'Agent Memory',
      '/experiments/agent-comparison': 'Agent Comparison',
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
      || (path.startsWith('/datasets/reports/') ? 'Report Detail' : null)
      || (path.startsWith('/datasets/') ? 'Dataset Detail' : null)
      || (path.startsWith('/projects/') && path.endsWith('/playbook') ? 'Playbook' : null)
      || (path.startsWith('/projects/') && path.includes('/subjects/') ? 'Subject Detail' : null)
      || (path.startsWith('/projects/') ? 'Project Detail' : null)
      || (path.startsWith('/quality/') ? 'Quality' : null)
      || (path.startsWith('/admin/') ? 'Admin' : null)
      || 'core-ai';
    document.title = `${title} - core-ai`;
  }, [location.pathname]);

  const [expandedNav, setExpandedNav] = useState<string | null>(null);

  const isRouteActive = (to: string, pathname: string): boolean => {
    if (to === '/traces') return pathname === '/traces' || pathname.startsWith('/traces/');
    if (to === '/observability') return pathname === '/traces' || pathname.startsWith('/traces/') || pathname === '/generations' || pathname.startsWith('/generations/') || pathname === '/calls';
    if (to === '/registry') return isRegistryPath(pathname);
    if (to === '/gateway') return pathname === '/gateway' || pathname === '/settings/gateway';
    if (to === '/admin') return pathname === '/admin' || pathname.startsWith('/admin/');
    if (to === '/tasks') return pathname === '/admin/tasks' || pathname.startsWith('/runs/');
    if (to === '/settings') return pathname === '/settings' || pathname === '/settings/api-keys';
    if (to === '/projects') return pathname === '/projects' || pathname.startsWith('/projects/');
    if (to === '/for-you') return pathname === '/for-you' || pathname.startsWith('/for-you/');
    if (to === '/experiments') return pathname === '/experiments' || pathname.startsWith('/experiments/');
    return pathname === to;
  };

  const canSeeRegistry = REGISTRY_PERMISSIONS.some(permission => hasPermission(user?.permissions, permission));
  const canSeeAdmin = user?.role === 'admin'
    || canManageSystem
    || canManageSnapshots
    || ['user.manage', 'task.view', 'costalert.manage'].some(permission => hasPermission(user?.permissions, permission));

  const navItems: NavItem[] = [
    { to: '/for-you', icon: Star, label: 'For You', show: true, permission: 'dashboard.view', children: [
      { to: '/for-you/artifacts', icon: Files, label: 'Artifacts', show: true, permission: 'dashboard.view' },
    ]},
    { to: '/chat', icon: MessageCircle, label: 'Chat', show: caps.chat, permission: 'chat.use' },
    { to: '/projects', icon: FolderKanban, label: 'Projects', show: true, permission: 'project.view' },
    { to: '/observability', icon: Gauge, label: 'Observation', show: caps.traces, permission: 'trace.view', children: [
      { to: '/traces', icon: Activity, label: 'Traces', show: caps.traces, permission: 'trace.view' },
      { to: '/generations', icon: Cpu, label: 'Generations', show: caps.traces, permission: 'trace.view' },
      { to: '/calls', icon: Terminal, label: 'Hub Calls', show: caps.traces, permission: 'trace.view' },
    ]},
    { to: '/registry', icon: Library, label: 'Registry', show: canSeeRegistry },
    { to: '/experiments', icon: FlaskConical, label: 'Experiment', show: true, permission: 'experiment.view', children: [
      { to: '/experiments/playground', icon: Play, label: 'Playground', show: true, permission: 'experiment.view' },
      { to: '/experiments/replay', icon: RotateCcw, label: 'Replay Debug', show: true, permission: 'experiment.view' },
      { to: '/experiments/memory', icon: Brain, label: 'Agent Memory', show: true, permission: 'experiment.view' },
      { to: '/experiments/agent-comparison', icon: GitCompare, label: 'Agent Comparison', show: true, permission: 'experiment.view' },
    ]},
    { to: '/gateway', icon: PlugZap, label: 'Gateway', show: true, permission: 'gateway.manage' },
  ].filter(item => item.show && (!item.permission || hasPermission(user?.permissions, item.permission)));

  const footerActive = (to: string) => {
    const active = isRouteActive(to, location.pathname);
    return {
      color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
      background: active ? 'var(--color-primary-bg)' : 'transparent',
    };
  };

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
            const visibleChildren = (children ?? []).filter(c => c.show && (!c.permission || hasPermission(user?.permissions, c.permission)));
            const hasChildren = visibleChildren.length > 0;
            const anyChildActive = hasChildren && visibleChildren.some(c => isRouteActive(c.to, location.pathname));
            const selfActive = isRouteActive(to, location.pathname);
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
                    {visibleChildren.map(child => {
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
        <div className="p-2 border-t space-y-1 shrink-0" style={{ borderColor: 'var(--color-border)' }}>
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
            style={footerActive('/settings')}>
            <Settings size={16} />
            {!collapsed && 'Settings'}
          </button>
          {canSeeAdmin && (
            <button onClick={() => navigate('/admin')}
              className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm w-full transition-colors cursor-pointer ${collapsed ? 'justify-center' : ''}`}
              style={footerActive('/admin')}>
              <ShieldCheck size={16} />
              {!collapsed && 'Admin'}
            </button>
          )}
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
          <a href="/downloads/desktop"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Download core-ai-desktop"
            title="Download core-ai-desktop (Windows x64 / macOS Apple Silicon)"
            className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white w-full transition-opacity cursor-pointer hover:opacity-90 ${collapsed ? 'justify-center' : ''}`}
            style={{ background: 'var(--color-primary)' }}>
            <Download size={16} />
            {!collapsed && 'Download Desktop'}
          </a>
        </div>
      </aside>
      <main className="flex-1 overflow-auto" style={{ background: 'var(--color-bg)' }}>
        <Outlet />
      </main>
      <QuickActionDialog />
    </div>
  );
}
