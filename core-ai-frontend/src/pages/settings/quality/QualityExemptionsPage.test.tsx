import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { qualityExemptionsApi, type QualityExemptionList } from '../../../api/quality';
import QualityExemptionsPage from './QualityExemptionsPage';

const EMPTY: QualityExemptionList = {
  agent: { policy_revision: 7, generation: 9, entries: [] },
  skill: { policy_revision: 5, generation: 6, entries: [] },
};

const WITH_ENTRIES: QualityExemptionList = {
  agent: {
    policy_revision: 7,
    generation: 9,
    entries: [{
      value: 'agent-1',
      subject_id: 'agent-1',
      name: 'Report Renderer',
      detail: 'agent-1',
      resolved: true,
    }],
  },
  skill: {
    policy_revision: 5,
    generation: 6,
    entries: [{
      value: 'core-ai/browser-use',
      subject_id: 'skill-1',
      name: 'browser-use',
      detail: 'core-ai/browser-use',
      resolved: true,
    }],
  },
};

describe('Quality exemptions page', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('lists agent and skill exemptions as subjects, not raw policy fields', async () => {
    vi.spyOn(qualityExemptionsApi, 'list').mockResolvedValue(WITH_ENTRIES);

    render(<QualityExemptionsPage />);

    expect(await screen.findByText('Report Renderer')).toBeTruthy();
    expect(screen.getByText('browser-use')).toBeTruthy();
    expect(screen.getByText('1 agent · 1 skill exempt')).toBeTruthy();
    expect(screen.getByText('core-ai/browser-use')).toBeTruthy();
  });

  it('adds a picked agent to the agent policy revision', async () => {
    vi.spyOn(qualityExemptionsApi, 'list').mockResolvedValue(EMPTY);
    vi.spyOn(qualityExemptionsApi, 'subjects').mockResolvedValue({
      subjects: [{
        id: 'agent-2',
        name: 'New Agent',
        detail: 'agent-2',
        already_exempt: false,
        automatic: false,
      }],
    });
    const add = vi.spyOn(qualityExemptionsApi, 'add').mockResolvedValue({
      ...EMPTY,
      agent: {
        policy_revision: 8,
        generation: 10,
        entries: [{ value: 'agent-2', subject_id: 'agent-2', name: 'New Agent', detail: 'agent-2', resolved: true }],
      },
    });

    render(<QualityExemptionsPage />);

    await userEvent.click(await screen.findByRole('button', { name: /add exemption/i }));
    await userEvent.click(await screen.findByText('New Agent'));
    await userEvent.click(screen.getAllByRole('button', { name: /add exemption/i }).at(-1)!);

    await waitFor(() => expect(add).toHaveBeenCalledWith('AGENT', 'agent-2', 9));
    expect(await screen.findByText(/is now exempt/)).toBeTruthy();
    expect(screen.getByText('New Agent')).toBeTruthy();
  });

  it('switches the picker to skills and adds the qualified skill', async () => {
    vi.spyOn(qualityExemptionsApi, 'list').mockResolvedValue(EMPTY);
    const subjects = vi.spyOn(qualityExemptionsApi, 'subjects').mockResolvedValue({
      subjects: [{
        id: 'skill-2',
        name: 'demo-skill',
        detail: 'demo/demo-skill',
        already_exempt: false,
        automatic: false,
      }],
    });
    const add = vi.spyOn(qualityExemptionsApi, 'add').mockResolvedValue({
      ...EMPTY,
      skill: {
        policy_revision: 6,
        generation: 7,
        entries: [{
          value: 'demo/demo-skill',
          subject_id: 'skill-2',
          name: 'demo-skill',
          detail: 'demo/demo-skill',
          resolved: true,
        }],
      },
    });

    render(<QualityExemptionsPage />);

    await userEvent.click(await screen.findByRole('button', { name: /add exemption/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Skill' }));

    expect(await screen.findByText('demo/demo-skill')).toBeTruthy();
    await waitFor(() => expect(subjects).toHaveBeenCalledWith('SKILL', ''));
    await userEvent.click(screen.getByText('demo-skill'));
    await userEvent.click(screen.getAllByRole('button', { name: /add exemption/i }).at(-1)!);

    await waitFor(() => expect(add).toHaveBeenCalledWith('SKILL', 'skill-2', 6));
  });

  it('removes an entry from the matching policy revision', async () => {
    vi.spyOn(qualityExemptionsApi, 'list').mockResolvedValue(WITH_ENTRIES);
    const remove = vi.spyOn(qualityExemptionsApi, 'remove').mockResolvedValue(EMPTY);
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    render(<QualityExemptionsPage />);

    await userEvent.click(await screen.findByRole('button', { name: /remove exemption report renderer/i }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith('AGENT', 'agent-1', 9));
    expect(await screen.findByText(/no longer exempt/)).toBeTruthy();
  });
});
