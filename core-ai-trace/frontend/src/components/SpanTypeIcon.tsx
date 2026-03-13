import { Bot, Brain, Wrench, GitBranch, Users } from 'lucide-react';

const icons: Record<string, typeof Bot> = {
  LLM: Brain,
  AGENT: Bot,
  TOOL: Wrench,
  FLOW: GitBranch,
  GROUP: Users,
};

const colors: Record<string, string> = {
  LLM: '#8b5cf6',
  AGENT: '#6366f1',
  TOOL: '#f59e0b',
  FLOW: '#06b6d4',
  GROUP: '#ec4899',
};

interface Props {
  type: string;
  size?: number;
}

export default function SpanTypeIcon({ type, size = 16 }: Props) {
  const Icon = icons[type] || Bot;
  return <Icon size={size} style={{ color: colors[type] || '#64748b' }} />;
}
