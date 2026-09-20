import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ToolsBlock from './ToolsBlock';

const compression = {
  type: 'compression' as const,
  before: 16,
  after: 14,
  contextTokens: 6979,
  maxContextTokens: 8192,
  triggerThreshold: 0.8,
};

describe('ToolsBlock', () => {
  it('carries the compression as a row of the tools it belongs to', () => {
    render(<ToolsBlock tools={[{ type: 'start', tool: 'bash', callId: '1', arguments: '{}' }]} compression={compression} />);

    expect(screen.getByText('Tools (1)')).not.toBeNull();
    expect(screen.getByText('Context compressed')).not.toBeNull();
    expect(screen.getByText('16 -> 14 messages · 6,979 / 8,192 tokens (85%) · threshold 80%')).not.toBeNull();
  });

  it('still shows the compression when the turn called no tool at all', () => {
    render(<ToolsBlock tools={[]} compression={compression} />);

    expect(screen.getByText('Context compressed')).not.toBeNull();
    expect(screen.queryByText('Tools (0)')).toBeNull();
  });

  it('renders nothing without tools or compression', () => {
    const { container } = render(<ToolsBlock tools={[]} />);

    expect(container.firstChild).toBeNull();
  });
});
