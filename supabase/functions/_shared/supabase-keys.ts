// Supabase API keys for Edge Functions, across both key generations.
// New projects inject SUPABASE_SECRET_KEYS / SUPABASE_PUBLISHABLE_KEYS (JSON, e.g. {"default":"sb_secret_..."});
// FLYTRACK_* lets us set a key by hand (e.g. when legacy JWT keys are disabled); older projects
// only have SUPABASE_SERVICE_ROLE_KEY / SUPABASE_ANON_KEY.

export type EnvGet = (name: string) => string | undefined;

const denoEnv: EnvGet = (name) => Deno.env.get(name);

/** First usable key from a JSON env value: {"default": k, ...} (default wins), ["k", ...] or "k". */
export function keyFromJson(raw: string | undefined): string | null {
  if (!raw?.trim()) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  const usable = (v: unknown): v is string => typeof v === "string" && v.trim().length > 0;
  if (usable(parsed)) return parsed.trim();
  if (Array.isArray(parsed)) return (parsed.find(usable) as string | undefined)?.trim() ?? null;
  if (parsed && typeof parsed === "object") {
    const o = parsed as Record<string, unknown>;
    if (usable(o.default)) return o.default.trim();
    const first = Object.values(o).find(usable);
    return first ? first.trim() : null;
  }
  return null;
}

function pick(env: EnvGet, jsonName: string, plainNames: string[]): string | null {
  const fromJson = keyFromJson(env(jsonName));
  if (fromJson) return fromJson;
  for (const name of plainNames) {
    const v = env(name)?.trim();
    if (v) return v;
  }
  return null;
}

/** Service key (bypasses RLS). Server-side only, never sent to clients or logged. */
export function secretKey(env: EnvGet = denoEnv): string | null {
  return pick(env, "SUPABASE_SECRET_KEYS", ["FLYTRACK_SECRET_KEY", "SUPABASE_SERVICE_ROLE_KEY"]);
}

/** Client key, used to verify a caller's JWT with the user's own privileges. */
export function publishableKey(env: EnvGet = denoEnv): string | null {
  return pick(env, "SUPABASE_PUBLISHABLE_KEYS", ["FLYTRACK_PUBLISHABLE_KEY", "SUPABASE_ANON_KEY"]);
}
