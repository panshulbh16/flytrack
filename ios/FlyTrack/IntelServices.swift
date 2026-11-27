import FlyTrackCore
import Foundation
import Supabase
import WidgetKit

// Backend adapter for the P3 flight intelligence RPCs (docs/P3_CONTRACTS.md A2, A5, B1–B4, B7) and the widget
// snapshot writer (A4). Every adapter returns `.notConfigured` when the xcconfig keys are blank and never throws.

private struct FlightIdParams: Encodable, Sendable { var p_flight_id: String }
private struct PredictionParams: Encodable, Sendable { var p_flight_id: String; var p_lang: String }
private struct IataParams: Encodable, Sendable { var p_iata: String }
private struct TrendsParams: Encodable, Sendable { var p_iata: String; var p_days: Int }

/// `flight_timeline`, `delay_prediction`, `gate_prediction`, `taxi_stats`, `my_connections`, `airport_trends`, and the
/// AIRCRAFT_SWAP rows of `flight_events` (RLS shows them only to callers who are Pro for the flight).
@MainActor
final class LiveFlightIntel: FlightIntelSource {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func timeline(flightId: String) async -> RemoteResult<[TimelineItem]> {
        await backend.call { client, _ in
            try parseFlightTimeline(try await client.rpc("flight_timeline", params: FlightIdParams(p_flight_id: flightId)).execute().data)
        }
    }

    func delayPrediction(flightId: String, language: String) async -> RemoteResult<DelayPrediction?> {
        await backend.call { client, _ in
            try parseDelayPrediction(try await client.rpc(
                "delay_prediction", params: PredictionParams(p_flight_id: flightId, p_lang: language)
            ).execute().data)
        }
    }

    func gatePrediction(flightId: String) async -> RemoteResult<GatePrediction?> {
        await backend.call { client, _ in
            try parseGatePrediction(try await client.rpc("gate_prediction", params: FlightIdParams(p_flight_id: flightId)).execute().data)
        }
    }

    func taxiStats(iata: String) async -> RemoteResult<TaxiStats?> {
        await backend.call { client, _ in
            try parseTaxiStats(try await client.rpc("taxi_stats", params: IataParams(p_iata: iata)).execute().data)
        }
    }

    func aircraftSwaps(flightId: String) async -> RemoteResult<[AircraftSwap]> {
        await backend.call { client, _ in
            try parseAircraftSwaps(try await client.from("flight_events")
                .select("created_at,payload")
                .eq("flight_id", value: flightId)
                .eq("type", value: AlertType.aircraftSwap.wire)
                .order("created_at", ascending: true)
                .execute()
                .data)
        }
    }

    func connections() async -> RemoteResult<[FlightConnection]> {
        await backend.call { client, _ in
            try parseConnections(try await client.rpc("my_connections").execute().data)
        }
    }

    func airportTrends(iata: String, days: Int) async -> RemoteResult<AirportTrends?> {
        await backend.call { client, _ in
            try parseAirportTrends(try await client.rpc("airport_trends", params: TrendsParams(p_iata: iata, p_days: days)).execute().data)
        }
    }
}

/// Writes the widget snapshot into the App Group `group.app.flytrack` and asks WidgetKit to reload, on every sync
/// (`LiveFlightRepository.onCacheChanged`). An unchanged list does not reload the widget (WidgetKit budgets reloads).
@MainActor
final class WidgetPublisher {
    private let defaults = UserDefaults(suiteName: WidgetContract.appGroup)
    private var lastFlights: [WidgetFlight]?

    func publish(flights: [Flight], now: Date) {
        guard let defaults else { return }
        let snapshot = widgetSnapshot(flights: flights, now: now)
        guard snapshot.flights != lastFlights, let data = WidgetSnapshotCodec.encode(snapshot) else { return }
        lastFlights = snapshot.flights
        defaults.set(data, forKey: WidgetContract.snapshotKey)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetContract.kind)
    }

    /// Sign-out or a user switch: the widget must not keep the previous user's flights.
    func clear() {
        lastFlights = nil
        defaults?.removeObject(forKey: WidgetContract.snapshotKey)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetContract.kind)
    }
}
