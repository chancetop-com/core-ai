import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CompressionBlock from './CompressionBlock';

describe('CompressionBlock', () => {
  it('shows the message counts and the context occupancy of the compression', () => {
    render(<CompressionBlock seg={{
      type: 'compression',
      before: 16,
      after: 14,
      contextTokens: 6979,
      maxContextTokens: 8192,
      triggerThreshold: 0.8,
    }} />);

    expect(screen.getByText('Context compressed')).not.toBeNull();
    expect(screen.getByText('16 -> 14 messages · 6,979 / 8,192 tokens (85%) · threshold 80%')).not.toBeNull();
  });

  it('still reports the counts when the server sent no usage', () => {
    render(<CompressionBlock seg={{ type: 'compression', before: 66, after: 29 }} />);

    expect(screen.getByText('66 -> 29 messages')).not.toBeNull();
  });
});
