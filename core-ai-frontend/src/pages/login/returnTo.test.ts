import { describe, expect, it } from 'vitest';

import { safeReturnTo } from './returnTo';

describe('safeReturnTo', () => {
  it('accepts deep links inside the SEO operations console', () => {
    expect(safeReturnTo('/seo-ops/tasks/1?tab=evidence')).toBe(
      '/seo-ops/tasks/1?tab=evidence',
    );
    expect(safeReturnTo('/seo-ops')).toBe('/seo-ops');
  });

  it.each([
    'https://evil.example/seo-ops',
    '//evil.example/seo-ops',
    '/login',
    '/agents',
    '/seo-ops\\evil',
    '/seo-ops\n/evil',
  ])('rejects unsafe or unrelated return target %s', (target) => {
    expect(safeReturnTo(target)).toBeNull();
  });

  it('rejects absent return targets and lookalike prefixes', () => {
    expect(safeReturnTo(null)).toBeNull();
    expect(safeReturnTo('')).toBeNull();
    expect(safeReturnTo('/seo-ops-admin')).toBeNull();
  });
});
