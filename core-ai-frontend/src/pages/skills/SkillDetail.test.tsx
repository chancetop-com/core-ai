import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type SkillDefinition } from '../../api/client';
import { ApiError, skillQualityApi, skillTelemetryApi, type SkillPapercut } from '../../api/quality';
import { useAuth } from '../../api/auth';
import SkillDetail from './SkillDetail';

vi.mock('../../api/auth', () => ({
  useAuth: vi.fn(),
}));

const DOMAIN_SKILL: SkillDefinition = {
  id: 'skill-1',
  namespace: 'team',
  name: 'demo-skill',
  qualified_name: 'team/demo-skill',
  description: 'Do demo work. Use when asked. Scope: demo only.',
  source_type: 'UPLOAD',
  tier: 'DOMAIN',
  allowed_tools: [],
  metadata: { version: '1.0.0', author: 'team', tags: 'demo' },
  version: '1.0.0',
  user_id: 'alice',
  created_at: '2026-11-28T00:00:00Z',
  updated_at: '2026-11-28T00:00:00Z',
};

function renderDetail() {
  render(
    <MemoryRouter initialEntries={['/skills/skill-1']}>
      <Routes>
        <Route path="/skills/:id" element={<SkillDetail />} />
        <Route path="/skills" element={<div>skills list</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('SkillDetail governance UI', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.mocked(useAuth).mockReturnValue({
      user: { apiKey: 'k1', userId: 'admin-1', name: 'Admin', role: 'admin', permissions: [] },
      login: vi.fn(),
      logout: vi.fn(),
    });
    vi.spyOn(api.skills, 'get').mockResolvedValue(DOMAIN_SKILL);
    vi.spyOn(skillTelemetryApi, 'getTelemetry').mockResolvedValue({
      skill_id: 'skill-1',
      qualified_name: 'team/demo-skill',
      current_digest: 'sha-1',
      telemetry_available: true,
      total_invoke_count: 14,
      total_resource_read_count: 6,
      total_materialize_fail_count: 0,
      total_attributed_script_calls: 5,
      total_attributed_script_errors: 1,
      open_papercut_count: 1,
      last_invoked_at: '2026-11-28T03:00:00Z',
      versions: [],
    });
    vi.spyOn(skillTelemetryApi, 'listPapercuts').mockResolvedValue({
      telemetry_available: true,
      papercuts: [
        {
          id: 'pc-1',
          skill_id: 'skill-1',
          qualified_name: 'team/demo-skill',
          package_digest: 'sha-1',
          category: 'SCRIPT_ERROR',
          status: 'OPEN',
          summary: 'Helper script fails on empty workspace',
          latest_observed_error: 'Authorization: Bearer *** failed with 401',
          suggested_fix: 'Check workspace flag before invoking script',
          occurrence_count: 3,
          last_seen_at: '2026-11-28T03:10:00Z',
        },
      ],
    });
    vi.spyOn(skillQualityApi, 'check').mockResolvedValue({
      run_id: 'run-1',
      gate_result: 'PASSED',
      warn_count: 2,
      issues: [
        {
          rule_id: 'SKILL.PROGRESSIVE_DISCLOSURE',
          rule_revision: 1,
          rule_name: 'Progressive disclosure',
          rule_requirement: '5 sections',
          status: 'FAIL',
          enforcement: 'WARN',
          message: 'No Workflow section was found.',
          suggestion: 'Add a Workflow section.',
        },
        {
          rule_id: 'SKILL.EVAL_DATASET',
          rule_revision: 1,
          rule_name: 'Eval dataset',
          rule_requirement: 'eval/eval.json required',
          status: 'FAIL',
          enforcement: 'WARN',
          message: 'eval/eval.json is not packaged.',
          suggestion: 'Add eval/eval.json.',
        },
      ],
    });
  });

  it('shows tier badge and prominent WARN banner for DOMAIN missing sections and eval', async () => {
    renderDetail();

    expect((await screen.findByTestId('skill-tier-badge')).textContent).toContain('DOMAIN');
    const banner = await screen.findByTestId('domain-quality-warn-banner');
    expect(banner.textContent).toContain('SKILL.PROGRESSIVE_DISCLOSURE');
    expect(banner.textContent).toContain('No Workflow section was found.');
    expect(banner.textContent).toContain('SKILL.EVAL_DATASET');
    expect(banner.textContent).toContain('eval/eval.json is not packaged.');
  });

  it('hides WARN banner when skill is CORE', async () => {
    vi.spyOn(api.skills, 'get').mockResolvedValue({ ...DOMAIN_SKILL, tier: 'CORE' });
    renderDetail();

    expect((await screen.findByTestId('skill-tier-badge')).textContent).toContain('CORE');
    expect(screen.queryByTestId('domain-quality-warn-banner')).toBeNull();
  });

  it('hides Manage Tier entry from non-admin users', async () => {
    vi.mocked(useAuth).mockReturnValue({
      user: { apiKey: 'k2', userId: 'user-1', name: 'User', role: 'user', permissions: [] },
      login: vi.fn(),
      logout: vi.fn(),
    });

    renderDetail();

    expect(await screen.findByTestId('skill-tier-badge')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /manage tier/i })).toBeNull();
  });

  it('renders 422 missing rule details and 409 quota exceeded error in admin tier modal', async () => {
    const updateSpy = vi.spyOn(skillQualityApi, 'updateTier');
    updateSpy.mockRejectedValueOnce(new ApiError(
      'Skill not published: 1 quality rule(s) failed.',
      422,
      'SKILL_QUALITY_REJECTED',
      {
        run_id: 'r1',
        gate_result: 'REJECTED',
        issues: [{
          rule_id: 'SKILL.EVAL_DATASET',
          rule_revision: 1,
          rule_name: 'Eval dataset',
          rule_requirement: 'eval/eval.json required',
          status: 'FAIL',
          enforcement: 'BLOCK',
          message: 'eval/eval.json is missing for CORE.',
          suggestion: 'Package eval/eval.json.',
        }],
      },
    ));
    updateSpy.mockRejectedValueOnce(new ApiError(
      'ALWAYS_ON skill quota exceeded (10/10)',
      409,
      'ALWAYS_ON_QUOTA_EXCEEDED',
    ));

    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: /manage tier/i }));

    await userEvent.selectOptions(screen.getByLabelText(/target tier/i), 'CORE');
    await userEvent.click(screen.getByRole('button', { name: /confirm tier change/i }));

    await waitFor(() => {
      const alert = screen.getByRole('alert');
      expect(alert.textContent).toContain('SKILL.EVAL_DATASET');
      expect(alert.textContent).toContain('eval/eval.json is missing for CORE.');
    });

    await userEvent.selectOptions(screen.getByLabelText(/target tier/i), 'ALWAYS_ON');
    await userEvent.click(screen.getByRole('button', { name: /confirm tier change/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('ALWAYS_ON Quota Exceeded (409)');
    });
  });

  it('requires a reason when demoting a skill', async () => {
    vi.spyOn(api.skills, 'get').mockResolvedValue({ ...DOMAIN_SKILL, tier: 'CORE' });
    const updateSpy = vi.spyOn(skillQualityApi, 'updateTier');

    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: /manage tier/i }));
    await userEvent.selectOptions(screen.getByLabelText(/target tier/i), 'DOMAIN');
    await userEvent.click(screen.getByRole('button', { name: /confirm tier change/i }));

    expect((await screen.findByRole('alert')).textContent).toMatch(/reason is required/i);
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('renders runtime health metrics, papercuts, and lets owner/admin update papercut status', async () => {
    const resolved: SkillPapercut = {
      id: 'pc-1',
      skill_id: 'skill-1',
      qualified_name: 'team/demo-skill',
      package_digest: 'sha-1',
      category: 'SCRIPT_ERROR',
      status: 'RESOLVED',
      summary: 'Helper script fails on empty workspace',
      latest_observed_error: 'Authorization: Bearer *** failed with 401',
      suggested_fix: 'Check workspace flag before invoking script',
      occurrence_count: 3,
      resolved_by: 'admin-1',
      last_seen_at: '2026-11-28T03:10:00Z',
    };
    const updateStatusSpy = vi.spyOn(skillTelemetryApi, 'updatePapercutStatus').mockResolvedValue(resolved);

    renderDetail();

    const row = await screen.findByTestId('papercut-row-pc-1');
    expect((await screen.findByTestId('metric-invocations')).textContent).toContain('14');
    expect((await screen.findByTestId('metric-resource-reads')).textContent).toContain('6');
    expect((await screen.findByTestId('metric-script-errors')).textContent).toContain('1 / 5');
    expect(row.textContent).toContain('x3');
    expect(row.textContent).toContain('Authorization: Bearer ***');

    await userEvent.click(within(row).getByRole('button', { name: /^resolve$/i }));

    await waitFor(() => {
      expect(updateStatusSpy).toHaveBeenCalledWith('skill-1', 'pc-1', 'RESOLVED');
      expect(screen.getByTestId('papercut-status-pc-1').textContent).toBe('RESOLVED');
    });
  });

  it('hides papercut status controls from non-owner regular users', async () => {
    vi.mocked(useAuth).mockReturnValue({
      user: { apiKey: 'k2', userId: 'bob', name: 'Bob', role: 'user', permissions: [] },
      login: vi.fn(),
      logout: vi.fn(),
    });

    renderDetail();

    const row = await screen.findByTestId('papercut-row-pc-1');
    expect(within(row).queryByRole('button', { name: /^resolve$/i })).toBeNull();
    expect(within(row).queryByRole('button', { name: /^ignore$/i })).toBeNull();
  });
});
