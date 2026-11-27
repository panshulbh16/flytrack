package app.flytrack.wear.ui

/** Plain English, same wording as res/values/strings.xml. */
object FakeWearStrings : WearStrings {
    override fun status(status: WatchStatus): String = when (status) {
        WatchStatus.ON_TIME -> "On time"
        WatchStatus.DELAYED -> "Delayed"
        WatchStatus.CHECK_IN -> "Check-in open"
        WatchStatus.BOARDING -> "Boarding"
        WatchStatus.GATE_CLOSED -> "Gate closed"
        WatchStatus.DEPARTED -> "Departed"
        WatchStatus.IN_AIR -> "In the air"
        WatchStatus.LANDED -> "Landed"
        WatchStatus.CANCELLED -> "Cancelled"
        WatchStatus.DIVERTED -> "Diverted"
    }

    override fun delayedBy(minutes: Int) = "Delayed $minutes min"
    override fun route(dep: String, arr: String) = "$dep → $arr"
    override fun gate(gate: String) = "Gate $gate"
    override fun terminal(terminal: String) = "T$terminal"
    override fun belt(belt: String) = "Belt $belt"
    override fun departs(time: String) = "Departs $time"
    override fun join(parts: List<String>) = parts.joinToString(" · ")
}
