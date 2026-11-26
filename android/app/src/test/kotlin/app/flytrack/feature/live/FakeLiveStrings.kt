package app.flytrack.feature.live

/** Plain English, same wording as strings_live.xml. */
object FakeLiveStrings : LiveStrings {
    override fun statusWord(word: LiveStatusWord): String = when (word) {
        LiveStatusWord.ON_TIME -> "On time"
        LiveStatusWord.DELAYED -> "Delayed"
        LiveStatusWord.CHECK_IN -> "Check-in open"
        LiveStatusWord.BOARDING -> "Boarding"
        LiveStatusWord.GATE_CLOSED -> "Gate closed"
        LiveStatusWord.DEPARTED -> "Departed"
        LiveStatusWord.IN_AIR -> "In the air"
        LiveStatusWord.LANDED -> "Landed"
        LiveStatusWord.CANCELLED -> "Cancelled"
        LiveStatusWord.DIVERTED -> "Diverted"
    }

    override fun delayedBy(minutes: Int) = "Delayed $minutes min"
    override fun route(dep: String, arr: String) = "$dep → $arr"
    override fun title(designator: String, route: String) = "$designator · $route"
    override fun departs(time: String) = "Departs $time"
    override fun arrives(time: String) = "Arrives $time"
    override fun landedAt(time: String) = "Landed $time"
    override fun gate(gate: String) = "Gate $gate"
    override fun terminal(terminal: String) = "T$terminal"
    override fun belt(belt: String) = "Belt $belt"
    override fun estimated() = "Estimated"
    override fun join(parts: List<String>) = parts.joinToString(" · ")
    override fun countdownDays(days: Int) = if (days == 1) "1 day" else "$days days"
    override fun countdownHoursMinutes(hours: Int, minutes: Int) = "%dh %02dm".format(hours, minutes)
    override fun countdownMinutes(minutes: Int) = "$minutes min"
    override fun departsIn(span: String) = "Departs in $span"
    override fun landsIn(span: String) = "Lands in $span"
}
