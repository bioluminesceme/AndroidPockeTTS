package nl.user.pockettest

/** Called on the main thread. Idle closure uses one callback, without polling. */
class ActivityWorkSchedule(
    private val render: () -> Unit,
    private val scheduleRefresh: () -> Unit,
    private val cancelRefresh: () -> Unit,
    private val scheduleClose: () -> Unit,
    private val cancelClose: () -> Unit,
    private val now: () -> Long,
    private val close: () -> Unit,
) {
    private var visible = false
    private var idlePending = false
    private var idleDeadline = 0L
    private var closed = false

    fun resume() { visible = true }
    fun pause() { visible = false; cancelRefresh() }

    fun update(playing: Boolean, working: Boolean, interaction: Boolean = false) {
        if (closed) return
        cancelRefresh()
        if (!working && idlePending && now() >= idleDeadline) {
            destroy()
            close()
            return
        }
        if (working) {
            cancelClose()
            idlePending = false
        } else if (!idlePending || interaction) {
            cancelClose()
            idleDeadline = now() + IDLE_MILLIS
            scheduleClose()
            idlePending = true
        }
        if (visible) {
            render()
            if (playing) scheduleRefresh()
        }
    }

    fun destroy() {
        closed = true
        pause()
        cancelClose()
        idlePending = false
    }

    companion object { const val IDLE_MILLIS = 10 * 60 * 1000L }
}
