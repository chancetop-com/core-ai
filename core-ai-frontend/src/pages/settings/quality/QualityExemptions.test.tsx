import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { QualityAdminApi } from '../../../api/quality';
import QualityExemptions from './QualityExemptions';

const activate = vi.fn();

function api(): QualityAdminApi {
  return { activate } as unknown as QualityAdminApi;
}

describe('Quality exemptions', () => {
  beforeEach(() => {
    activate.mockReset();
    activate.mockResolvedValue({});
  });

  it('saves edited agent ids as a new policy activation', async () => {
    render(
      <QualityExemptions
        api={api()}
        policy={{ revision: 3, generation: 4, exemptions: ['agent-1'] }}
        kind="agent"
        onSaved={() => undefined}
      />,
    );

    const textarea = screen.getByPlaceholderText('agent-id-1');
    await userEvent.clear(textarea);
    await userEvent.type(textarea, 'agent-1\nagent-2');
    await userEvent.click(screen.getByRole('button', { name: /save exemptions/i }));

    expect(activate).toHaveBeenCalledWith({
      expected_generation: 4,
      change_reason: 'Update quality exemptions',
      exemptions: ['agent-1', 'agent-2'],
    });
  });

  it('shows the existing whitelist and built-in hint for skills', () => {
    render(
      <QualityExemptions
        api={api()}
        policy={{ revision: 2, generation: 2, exemptions: ['core-ai/browser-use'] }}
        kind="skill"
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByDisplayValue('core-ai/browser-use')).toBeTruthy();
    expect(screen.getByText(/built-in/i)).toBeTruthy();
    expect(screen.getByText(/1 listed/)).toBeTruthy();
  });
});
