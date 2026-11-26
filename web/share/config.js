// Deployment settings for the public share page. Edit per environment; no build step.
// SUPABASE_URL: the project URL (https://<ref>.supabase.co, or http://127.0.0.1:54321 for `supabase start`).
// SUPABASE_ANON_KEY: optional. shared-flight runs with verify_jwt = false and needs no key; set the
// publishable/anon key here only if your gateway asks for an `apikey` header.
// If you host the page on another Supabase project or a custom domain, also update connect-src in index.html.
window.HEYGATE_SHARE_CONFIG = {
  SUPABASE_URL: "https://YOUR-PROJECT-REF.supabase.co",
  SUPABASE_ANON_KEY: "",
  REFRESH_SECONDS: 60,
};
