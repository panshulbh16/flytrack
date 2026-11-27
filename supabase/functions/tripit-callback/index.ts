// GET from TripIt after the user approved (verify_jwt = false): ?oauth_token=<request token>.
// TripIt sends no oauth_verifier, so this request cannot tell who approved: it NEVER exchanges the token
// (login CSRF fix, migration 35). It stores sha256 of a one-time code on the pending row and redirects to
// heygate://tripit/callback?code=<code>; the app finishes with tripit-complete under the signed-in user's JWT.
// Unknown / expired / already used token → heygate://tripit/failed?error=expired. The 302 carries a small HTML
// page with the same deep link as a button, for browsers that do not follow a redirect to a custom scheme.
import { log } from "../_shared/http.ts";
import { callbackPage, callbackTripIt } from "../_shared/tripit.ts";
import { buildTripItDeps } from "../_shared/tripit-deps.ts";

function redirect(to: string): Response {
  return new Response(callbackPage(to), {
    status: 302,
    headers: {
      location: to,
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
    },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "GET") return new Response("method", { status: 405 });
  try {
    const r = await callbackTripIt(await buildTripItDeps(), new URL(req.url).searchParams);
    return redirect(r.redirect);
  } catch (e) {
    log("tripit-callback failed", { error: e instanceof Error ? e.message : String(e) });
    return redirect("heygate://tripit/failed?error=internal");
  }
});
