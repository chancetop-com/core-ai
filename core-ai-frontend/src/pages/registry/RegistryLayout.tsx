import { BarChart3, Bot, Calendar, Database, FileText, Key, Network, Puzzle, Radio, ShieldCheck, Sparkles, Webhook, Workflow, Wrench, Zap } from 'lucide-react';
import { Navigate } from 'react-router-dom';
import SectionLayout, { type SectionNavItem } from '../../components/SectionLayout';
import { useCapabilities } from '../../api/capabilities';
import { useAuth } from '../../api/auth';
import { hasPermission } from '../../api/permissions';

function useRegistryNavItems(): SectionNavItem[] {
  const caps = useCapabilities();
  const { user } = useAuth();
  const can = (permission: string) => hasPermission(user?.permissions, permission);

  return [
    { to: '/agents', icon: Bot, label: 'Agents', show: can('agent.view') },
    { to: '/workflows', icon: Workflow, label: 'Workflows', show: can('workflow.view') },
    { to: '/system-prompts', icon: FileText, label: 'System Prompts', show: caps.systemPrompts && can('prompt.view') },
    {
      label: 'Triggers', icon: Zap, show: can('trigger.view'), children: [
        { to: '/triggers/webhook', icon: Webhook, label: 'Webhook', show: can('trigger.view') },
        { to: '/triggers/channels', icon: Radio, label: 'Channels', show: can('trigger.view') },
        { to: '/triggers/openclaw', icon: Zap, label: 'OpenClaw', show: can('trigger.view') },
        { to: '/triggers/schedule', icon: Calendar, label: 'Schedule', show: can('trigger.view') },
      ],
    },
    {
      label: 'Tools', icon: Wrench, show: can('tool.view') || can('mcp.view') || can('apitool.view'), children: [
        { to: '/tools/builtin', icon: Wrench, label: 'Builtin Tools', show: can('tool.view') },
        { to: '/mcp', icon: Network, label: 'MCP Servers', show: can('mcp.view') },
        { to: '/api-tools', icon: Key, label: 'API Tools', show: can('apitool.view') },
      ],
    },
    { to: '/skills', icon: Sparkles, label: 'Skills', show: can('skill.view') },
    {
      to: '/datasets', end: true, icon: Database, label: 'Datasets', show: can('dataset.view'), children: [
        { to: '/datasets/reports', icon: BarChart3, label: 'Reports', show: caps.datasetReports && can('report.view') },
      ],
    },
    { to: '/plugins', icon: Puzzle, label: 'Plugins', show: can('plugin.view') },
    { to: '/quality', icon: ShieldCheck, label: 'Quality', show: can('agent.quality.manage') },
  ];
}

function firstVisiblePath(items: SectionNavItem[]): string | undefined {
  for (const item of items) {
    if (!item.show) continue;
    if (item.to) return item.to;
    const child = (item.children ?? []).find(entry => entry.show && entry.to);
    if (child?.to) return child.to;
  }
  return undefined;
}

/** Lands on the first registry page the current user may open instead of a page they would be bounced from. */
export function RegistryHome() {
  const items = useRegistryNavItems();
  return <Navigate to={firstVisiblePath(items) ?? '/for-you'} replace />;
}

export default function RegistryLayout() {
  return <SectionLayout title="Registry" items={useRegistryNavItems()} />;
}
