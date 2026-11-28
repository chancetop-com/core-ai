import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { customEventSegments } from '../utils';
import type { SkillAttachNoticeSegment } from '../types';
import SkillAttachNoticeCard from './SkillAttachNoticeCard';

describe('SkillAttachNoticeCard and skill_attach_request event mapping', () => {
  it('maps skill_attach_request custom event into a skill_attach_notice segment', () => {
    const segments = customEventSegments({
      name: 'skill_attach_request',
      data: JSON.stringify({
        qualified_name: 'payments/deploy-payments',
        tier: 'DOMAIN',
        namespace: 'payments',
        attached: false,
        description: 'Deploy payment service safely.',
        required_capabilities: ['tool:run_bash_command'],
      }),
    });

    expect(segments).toHaveLength(1);
    expect(segments[0]).toMatchObject({
      type: 'skill_attach_notice',
      qualifiedName: 'payments/deploy-payments',
      tier: 'DOMAIN',
      namespace: 'payments',
      description: 'Deploy payment service safely.',
      requiredCapabilities: ['tool:run_bash_command'],
    });
  });

  it('displays Not Attached notice without any unauthorized one-click load button', () => {
    const segment: SkillAttachNoticeSegment = {
      type: 'skill_attach_notice',
      qualifiedName: 'payments/deploy-payments',
      tier: 'DOMAIN',
      description: 'Deploy payment service safely.',
      requiredCapabilities: ['mcp:deploy/run'],
    };

    render(<SkillAttachNoticeCard segment={segment} />);

    expect(screen.getByTestId('skill-attach-notice-card').textContent).toContain('payments/deploy-payments');
    expect(screen.getByTestId('skill-unattached-badge').textContent).toContain('Not Attached');
    expect(screen.getByTestId('skill-tier-badge').textContent).toContain('DOMAIN');
    expect(screen.getByTestId('skill-attach-notice-card').textContent).toContain('mcp:deploy/run');
    expect(screen.queryByRole('button')).toBeNull();
  });
});
