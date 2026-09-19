/**
 * Link resolution for the in-console handbook (#869).
 *
 * docs/user-guide/*.md are authored for GitHub: relative links point at
 * neighbouring docs or escape to the wider docs/ tree (../client-onboarding.md),
 * and hash links target headings. Inside the console the relative ones would
 * 404, so they are resolved inside the docs/ tree and re-rooted at the matching
 * GitHub blob URL. Absolute http(s)/mailto links and pure hash links pass
 * through unchanged (hash links scroll locally).
 */
const BLOB_BASE = 'https://github.com/sijie-Z/miqro-gate/blob/develop';

export interface ResolvedDocLink {
  href: string;
  external: boolean;
}

export function resolveDocLink(href: string, currentDoc: string): ResolvedDocLink {
  const trimmed = href.trim();
  if (/^(https?:)?\/\//i.test(trimmed) || trimmed.startsWith('mailto:')) {
    return { href: trimmed, external: true };
  }
  if (trimmed.startsWith('#')) {
    return { href: trimmed, external: false };
  }
  // Anchor the relative target at docs/user-guide/<currentDoc's dir>, resolve
  // './' and '../' inside the docs/ tree, then map to the GitHub blob prefix.
  const base = ['docs', 'user-guide', ...currentDoc.split('/').slice(0, -1)];
  const segments = [...base, ...trimmed.split('/')];
  const out: string[] = [];
  for (const segment of segments) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') out.pop();
    else out.push(segment);
  }
  return { href: `${BLOB_BASE}/${out.join('/')}`, external: true };
}
