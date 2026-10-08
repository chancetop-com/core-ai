import { describe, expect, it } from 'vitest';
import { buildSkillMdContent, parseFrontmatter } from './skillFrontmatter';

const FOLDED = [
  '---',
  'name: restaurant-local-seo',
  'description: >',
  '  Run automated multi-dimensional local SEO audits on restaurant websites.',
  '  Use this skill whenever a user provides a restaurant website URL.',
  '---',
  '',
  '# Restaurant Local SEO Audit',
  '',
  'Body line.',
].join('\n');

function valuesOf(content: string): Record<string, string> {
  const values: Record<string, string> = {};
  for (const item of parseFrontmatter(content).keys) values[item.key] = item.value;
  return values;
}

describe('parseFrontmatter', () => {
  it('folds a block scalar instead of taking the indicator as the value', () => {
    const values = valuesOf(FOLDED);

    expect(values.name).toBe('restaurant-local-seo');
    expect(values.description).toBe(
      'Run automated multi-dimensional local SEO audits on restaurant websites. Use this skill whenever a user provides a restaurant website URL.',
    );
  });

  it('does not treat indented continuation lines as keys', () => {
    const content = ['---', 'description: >', '  Use this when the user asks: "audit" things.', '---', 'body'].join('\n');
    const parsed = parseFrontmatter(content);

    expect(parsed.keys.map(item => item.key)).toEqual(['description']);
    expect(parsed.keys[0].value).toBe('Use this when the user asks: "audit" things.');
    expect(parsed.body).toBe('body');
  });

  it('parses literal blocks with newlines and folded paragraphs', () => {
    const literal = valuesOf(['---', 'description: |-', '  line one', '  line two', '---', 'body'].join('\n'));
    expect(literal.description).toBe('line one\nline two');

    const paragraphs = valuesOf(['---', 'description: >', '  para one', '', '  para two', '---', 'body'].join('\n'));
    expect(paragraphs.description).toBe('para one\npara two');
  });

  it('joins block sequences for allowed-tools', () => {
    const values = valuesOf(['---', 'allowed-tools:', '  - tool-a', '  - tool-b', '---', 'body'].join('\n'));
    expect(values['allowed-tools']).toBe('tool-a tool-b');
  });

  it('keeps the whole content as body when there is no frontmatter', () => {
    const parsed = parseFrontmatter('# Just a body');

    expect(parsed.keys).toEqual([]);
    expect(parsed.body).toBe('# Just a body');
  });

  it('keeps the blank line after the frontmatter in the body', () => {
    const parsed = parseFrontmatter(FOLDED);

    expect(parsed.body).toBe('\n# Restaurant Local SEO Audit\n\nBody line.');
  });
});

describe('buildSkillMdContent', () => {
  it('round-trips an untouched folded description byte-identically', () => {
    const parsed = parseFrontmatter(FOLDED);
    const values = valuesOf(FOLDED);

    const rebuilt = buildSkillMdContent(parsed.keys, {
      name: values.name,
      description: values.description,
      allowedTools: [],
      version: '',
    }, parsed.body);

    expect(rebuilt).toBe(FOLDED);
  });

  it('replaces an edited description and keeps the body intact', () => {
    const parsed = parseFrontmatter(FOLDED);

    const rebuilt = buildSkillMdContent(parsed.keys, {
      name: 'restaurant-local-seo',
      description: 'New shorter description.',
      allowedTools: [],
      version: '',
    }, parsed.body);

    expect(rebuilt).not.toContain('description: >');
    expect(valuesOf(rebuilt).description).toBe('New shorter description.');
    expect(parseFrontmatter(rebuilt).body).toBe(parsed.body);
  });

  it('preserves frontmatter keys the editor does not expose', () => {
    const content = ['---', 'name: demo', 'license: MIT', 'description: short', '---', 'body'].join('\n');
    const parsed = parseFrontmatter(content);

    const rebuilt = buildSkillMdContent(parsed.keys, {
      name: 'demo',
      description: 'short',
      allowedTools: [],
      version: '2',
    }, parsed.body);

    expect(rebuilt).toContain('license: MIT');
    expect(rebuilt).toContain('version: 2');
    expect(rebuilt.indexOf('license: MIT')).toBeLessThan(rebuilt.indexOf('version: 2'));
  });

  it('quotes a changed value that would not parse as a plain scalar', () => {
    const parsed = parseFrontmatter(['---', 'name: demo', 'description: old', '---', 'body'].join('\n'));

    const rebuilt = buildSkillMdContent(parsed.keys, {
      name: 'demo',
      description: 'title: with colon',
      allowedTools: [],
      version: '',
    }, parsed.body);

    expect(rebuilt).toContain('description: "title: with colon"');
    expect(valuesOf(rebuilt).description).toBe('title: with colon');
  });

  it('renders a multi-line description as a literal block', () => {
    const parsed = parseFrontmatter(['---', 'name: demo', 'description: old', '---', 'body'].join('\n'));

    const rebuilt = buildSkillMdContent(parsed.keys, {
      name: 'demo',
      description: 'line one\nline two',
      allowedTools: [],
      version: '',
    }, parsed.body);

    expect(rebuilt).toContain('description: |-\n  line one\n  line two');
    expect(valuesOf(rebuilt).description).toBe('line one\nline two');
  });
});
