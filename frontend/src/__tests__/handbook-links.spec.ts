import { describe, expect, it } from 'vitest';
import { resolveDocLink } from '@/lib/handbook-links';

const BLOB = 'https://github.com/sijie-Z/miqro-gate/blob/develop';

describe('resolveDocLink (#869)', () => {
  it('rewrites sibling doc links to GitHub blob URLs', () => {
    expect(resolveDocLink('quickstart.md', 'README.md')).toEqual({
      href: `${BLOB}/docs/user-guide/quickstart.md`,
      external: true,
    });
  });

  it('lets ../ escape the user-guide directory inside the docs tree', () => {
    expect(resolveDocLink('../client-onboarding.md', 'user-guide.md').href).toBe(
      `${BLOB}/docs/client-onboarding.md`,
    );
    expect(resolveDocLink('../workbuddy-mcp-onboarding-sample.md', 'developer-guide.md').href).toBe(
      `${BLOB}/docs/workbuddy-mcp-onboarding-sample.md`,
    );
  });

  it('keeps anchors on rewritten paths', () => {
    expect(resolveDocLink('user-guide.md#faq', 'README.md').href).toBe(
      `${BLOB}/docs/user-guide/user-guide.md#faq`,
    );
  });

  it('passes absolute links through as external and hash links as internal', () => {
    expect(resolveDocLink('https://learn.microsoft.com/x', 'README.md')).toEqual({
      href: 'https://learn.microsoft.com/x',
      external: true,
    });
    expect(resolveDocLink('mailto:a@b.c', 'README.md').external).toBe(true);
    expect(resolveDocLink('#section', 'README.md')).toEqual({ href: '#section', external: false });
  });
});
