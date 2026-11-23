# FlyTrack — Project Rules & Notes (for any AI assistant: Claude, ChatGPT, Grok Bot, Cursor)

Repo: github.com/panshulbh16/flytrack (private). Owner: Panshul Bharadwaj.
Last updated: 23 Nov 2026.

## Rules
1. **Product:** India-first live flight tracker like Flighty — Android-first, AND iOS.
2. **Core product = instant notifications.** Gate change, delay and cancellation alerts must arrive
   even when the phone screen is off / app is killed.
3. **Android reliability:** Background restrictions are strict (Doze, OEM battery killers on Xiaomi,
   Oppo, Vivo, Samsung etc.). Use FCM (high-priority data/notification messages) driven by the
   Supabase backend, plus a proper foreground service only where justified (e.g. active-flight live
   tracking). Do NOT rely on on-device background polling alone.
4. **Backend:** Supabase — server-side polling of flights (deduped per unique flight, adaptive
   cadence) and push alerts for delay, gate and cancellation.
5. **Built so far:** flight add, flight list, flight card and detail screens, settings, push
   notifications, ticket import (Android). iOS app (HeyGate) also built by Cursor.
6. **Remaining:** home-screen widget, live (ongoing) notification, offline/airplane-mode estimate,
   map, payments. App NOT yet tested on a real device.
7. **User-facing language:** Hinglish/Hindi preferred.

## Working notes
- Never hardcode API keys; keep them in env / local.properties / Supabase secrets.
- Flight data provider sits behind a provider interface (AeroDataBox now; FlightAware later).
