// Called by pg_cron every 15 min (migration 20261126000013_airport_weather.sql). NOT callable by app users.
// Fetches METARs from aviationweather.gov (batches of 30 ICAO codes) for the Indian metros and every airport
// with a tracked flight in the next 24 h, and upserts public.airport_weather. Fog alerts are sent by the
// poll cycle (runFogAlerts), which reads this table.
import { type AirportWeatherRow, refreshAirportWeather, type WeatherStore, type WeatherTarget } from "../_shared/weather.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import { json, log, safeEqual } from "../_shared/http.ts";

function supabaseStore(): WeatherStore {
  const db = serviceClient();
  return {
    async targets() {
      const { data, error } = await db.rpc("weather_refresh_targets");
      if (error) throw new Error(`weather_refresh_targets: ${error.message}`);
      return (data ?? []) as WeatherTarget[];
    },
    async upsert(rows: AirportWeatherRow[]) {
      const { data, error } = await db.rpc("upsert_airport_weather", { p_rows: rows });
      if (error) throw new Error(`upsert_airport_weather: ${error.message}`);
      return Number(data ?? 0);
    },
  };
}

Deno.serve(async (req) => {
  const secret = Deno.env.get("CRON_SECRET");
  if (!secret || !safeEqual(req.headers.get("x-cron-secret") ?? "", secret)) {
    return json({ error: "unauthorized" }, 401);
  }
  const started = Date.now();
  try {
    const stats = await refreshAirportWeather({
      store: supabaseStore(),
      fetch,
      now: () => new Date(),
      log,
      timeoutMs: 15_000,
    });
    log("airport weather refreshed", { ...stats, ms: Date.now() - started });
    return json({ ok: true, ...stats, ms: Date.now() - started });
  } catch (e) {
    log("airport weather refresh failed", { error: String(e) });
    return json({ ok: false, error: String(e) }, 500);
  }
});
