package app.flytrack.feature.checkin

import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.CheckinRuleDto
import app.flytrack.data.remote.ShareCheckinApi

/** Answers [ruleFor] from [rules]; records the carriers asked for. */
class FakeCheckinRulesRepository(var rules: Map<String, CheckinRule> = BUILT_IN_RULES) : CheckinRulesRepository {
    val asked = mutableListOf<String>()
    override suspend fun ruleFor(carrierIata: String): CheckinRule? {
        asked += carrierIata
        return rules[carrierIata]
    }
}

/** In-memory backend for the share and check-in calls. */
class FakeShareCheckinApi : ShareCheckinApi {
    var token = "tok_abcdefghijklmnopqrstuv"
    var rules: List<CheckinRuleDto> = emptyList()
    var failure: BackendException? = null
    val calls = mutableListOf<String>()

    override suspend fun createFlightShare(flightId: String): String {
        calls += "createFlightShare($flightId)"
        failure?.let { throw it }
        return token
    }

    override suspend fun checkinRules(): List<CheckinRuleDto> {
        calls += "checkinRules"
        failure?.let { throw it }
        return rules
    }
}
