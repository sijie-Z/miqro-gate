/**
 * repo → project_id registry (CAA Spec v1.1 §5.3 step 2 / §7.4).
 *
 * The mapping is server-side truth, maintained by administrators under
 * `project_repositories` and served to the agent through the gateway
 * (`GET /v1/context-registry`, authenticated with the same virtual key that
 * the agent proxies — only repos of projects bound to that key are visible).
 * The agent caches it and never guesses: a repo key that is not registered
 * resolves to nothing.
 */

export type RegistryEntry = { repoKey: string; projectId: string; projectTag?: string };

export type SyncResult = { ok: boolean; count: number; fetchedAt: number; error?: string };

export class Registry {
  private entries = new Map<string, RegistryEntry>();
  private fetchedAt = 0;
  private lastError: string | undefined;

  lookup(repoKey: string): RegistryEntry | undefined {
    return this.entries.get(repoKey.toLowerCase());
  }

  size(): number {
    return this.entries.size;
  }

  lastSync(): { fetchedAt: number; error?: string } {
    return { fetchedAt: this.fetchedAt, error: this.lastError };
  }

  /** Replace the cache from the gateway. Never throws. */
  async sync(gatewayBaseUrl: string, virtualKey: string, timeoutMs = 10_000): Promise<SyncResult> {
    const url = new URL("/v1/context-registry", gatewayBaseUrl);
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      const res = await fetch(url, {
        headers: { authorization: `Bearer ${virtualKey}`, accept: "application/json" },
        signal: controller.signal,
      });
      clearTimeout(timer);
      if (!res.ok) {
        this.lastError = `HTTP ${res.status}`;
        return { ok: false, count: this.entries.size, fetchedAt: this.fetchedAt, error: this.lastError };
      }
      const body = (await res.json()) as { entries?: RegistryEntry[] } | RegistryEntry[];
      const list = Array.isArray(body) ? body : (body.entries ?? []);
      const next = new Map<string, RegistryEntry>();
      for (const entry of list) {
        if (typeof entry?.repoKey === "string" && typeof entry?.projectId === "string") {
          next.set(entry.repoKey.toLowerCase(), entry);
        }
      }
      this.entries = next;
      this.fetchedAt = Date.now();
      this.lastError = undefined;
      return { ok: true, count: next.size, fetchedAt: this.fetchedAt };
    } catch (e) {
      this.lastError = e instanceof Error ? e.message : String(e);
      return { ok: false, count: this.entries.size, fetchedAt: this.fetchedAt, error: this.lastError };
    }
  }

  /** Test seam. */
  load(entries: RegistryEntry[]): void {
    this.entries = new Map(entries.map((e) => [e.repoKey.toLowerCase(), e]));
    this.fetchedAt = Date.now();
  }
}
