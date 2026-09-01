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
  it('offers a terminal for a historical sandbox when the capability is enabled', () => {
    render(<SandboxBlock seg={readySegment({ historical: true })} terminalEnabled={true} onOpenTerminal={vi.fn()} />);

    expect(screen.getByTitle('Open terminal')).not.toBeNull();
  });

  it('does not offer a terminal when the capability is disabled', () => {
    render(<SandboxBlock seg={readySegment()} terminalEnabled={false} onOpenTerminal={vi.fn()} />);

    expect(screen.queryByTitle('Open terminal')).toBeNull();
  });

  it('does not offer a terminal while a live sandbox is pending', () => {
    render(<SandboxBlock
      seg={readySegment({ sandboxType: 'creating', sandboxId: 'pending' })}
      terminalEnabled={true}
      onOpenTerminal={vi.fn()}
    />);

    expect(screen.queryByTitle('Open terminal')).toBeNull();
  });

  it('opens a ready live sandbox terminal when the capability is enabled', () => {
    const onOpenTerminal = vi.fn();
    render(<SandboxBlock seg={readySegment()} terminalEnabled={true} onOpenTerminal={onOpenTerminal} />);

    fireEvent.click(screen.getByTitle('Open terminal'));

    expect(onOpenTerminal).toHaveBeenCalledWith({
      sandboxId: 'sandbox-1',
      hostname: undefined,
      ip: undefined,
      image: undefined,
    });
  });
});
