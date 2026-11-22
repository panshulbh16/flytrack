import { DEFAULT_CONFIG, type Deps } from "./engine.ts";
import { pusherFromEnv } from "./fcm.ts";
import { log } from "./http.ts";
import { providerFromEnv } from "./providers/index.ts";
import { serviceClient, SupabaseRepo } from "./supabase-repo.ts";

/** Production wiring. A fresh provider per call so onCall can carry request context (userId). */
export function buildDeps(opts: { userId?: string } = {}): Deps {
  const repo = new SupabaseRepo(serviceClient());
  const provider = providerFromEnv();
  provider.onCall = (info) => {
    void repo.logProviderCall({ provider: provider.name, ...info, userId: opts.userId ?? null });
  };
  const base = Deno.env.get("SUPABASE_URL");
  const hookSecret = Deno.env.get("WEBHOOK_SECRET");
  return {
    repo,
    provider,
    pusher: pusherFromEnv(),
    now: () => new Date(),
    config: {
      ...DEFAULT_CONFIG,
      webhookCallbackUrl: base && hookSecret
        ? `${base}/functions/v1/provider-webhook?token=${encodeURIComponent(hookSecret)}`
        : null,
    },
    log,
  };
}
