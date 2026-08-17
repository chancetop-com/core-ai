const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/;

/**
 * Allow login to hand control only to the separately deployed SEO Ops bundle.
 * Keeping this as a narrow allowlist prevents return_to from becoming an open
 * redirect while preserving the requested deep link.
 */
export function safeReturnTo(raw: string | null): string | null {
  if (!raw || CONTROL_CHARACTER.test(raw) || raw.includes('\\')) {
    return null;
  }

  if (raw !== '/seo-ops' && !raw.startsWith('/seo-ops/') && !raw.startsWith('/seo-ops?')) {
    return null;
  }

  if (raw.startsWith('//')) {
    return null;
  }

  return raw;
}
