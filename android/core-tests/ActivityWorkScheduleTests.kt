package nl.user.pockettest

fun runActivityWorkScheduleTests() {
    var now = 0L
    var closeAt: Long? = null
    var refreshPending = false
    var renders = 0
    var closed = false
    val schedule = ActivityWorkSchedule(
        render = { renders++ },
        scheduleRefresh = { refreshPending = true },
        cancelRefresh = { refreshPending = false },
        scheduleClose = { closeAt = now + ActivityWorkSchedule.IDLE_MILLIS },
        cancelClose = { closeAt = null },
        now = { now },
        close = { closed = true },
    )
    schedule.resume()
    schedule.update(false, false)
    check(renders == 1 && !refreshPending && closeAt == 600_000L)
    now = 120_000
    schedule.update(false, false)
    check(closeAt == 600_000L) { "State changes must not extend idle time" }
    schedule.update(false, false, interaction = true)
    check(closeAt == 720_000L && !refreshPending)
    schedule.update(true, true)
    check(closeAt == null && refreshPending)
    schedule.pause()
    check(!refreshPending)
    val previousRenders = renders
    schedule.update(true, true)
    check(renders == previousRenders && !refreshPending && closeAt == null)
    now = 800_000
    schedule.update(false, false)
    check(closeAt == 1_400_000L && !refreshPending)
    schedule.resume()
    schedule.update(false, true)
    check(closeAt == null && !refreshPending) { "Imports must not poll or close" }
    now = 900_000
    schedule.update(false, false)
    check(closeAt == 1_500_000L && !refreshPending)
    now = 1_499_999
    schedule.update(false, false)
    check(!closed)
    schedule.pause()
    now = 1_500_000
    schedule.update(false, false)
    check(closed && closeAt == null && !refreshPending)
    schedule.resume()
    schedule.update(false, false, interaction = true)
    check(closeAt == null && !refreshPending) { "Closed screens must not restart callbacks" }
    schedule.destroy()
    check(closeAt == null && !refreshPending)
    println("PASS: Idle updates stop; interaction resets 10-minute closure; playback and imports prevent closure; hidden screens do not refresh")
}
