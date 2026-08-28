import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SandboxSegment } from '../types';
import SandboxBlock from './SandboxBlock';

function readySegment(extra: Partial<SandboxSegment> = {}): SandboxSegment {
  return {
    type: 'sandbox',
    sandboxType: 'ready',
    sandboxId: 'sandbox-1',
    message: 'Sandbox is ready',
    ...extra,
  };
}

describe('SandboxBlock', () => {
  it('does not offer a terminal for a sandbox restored from history', () => {
    render(<SandboxBlock seg={readySegment({ historical: true })} onOpenTerminal={vi.fn()} />);

    expect(screen.queryByTitle('Open terminal')).toBeNull();
  });

  it('opens a ready live sandbox terminal', () => {
    const onOpenTerminal = vi.fn();
    render(<SandboxBlock seg={readySegment()} onOpenTerminal={onOpenTerminal} />);

    fireEvent.click(screen.getByTitle('Open terminal'));

    expect(onOpenTerminal).toHaveBeenCalledWith({
      sandboxId: 'sandbox-1',
      hostname: undefined,
      ip: undefined,
      image: undefined,
    });
  });

  it('does not offer a terminal while a live sandbox is pending', () => {
    render(<SandboxBlock
      seg={readySegment({ sandboxType: 'creating', sandboxId: 'pending' })}
      onOpenTerminal={vi.fn()}
    />);

    expect(screen.queryByTitle('Open terminal')).toBeNull();
  });
});
