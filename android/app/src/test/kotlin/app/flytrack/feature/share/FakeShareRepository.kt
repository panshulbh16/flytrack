package app.flytrack.feature.share

/** Returns [result]; records the flights shared. */
class FakeShareRepository(var result: ShareResult = ShareResult.Shared("https://heygate.app/f/#tok")) : ShareRepository {
    val calls = mutableListOf<String>()
    override suspend fun createShare(flightId: String): ShareResult {
        calls += flightId
        return result
    }
}
