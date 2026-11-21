package ir.v2rayng.compose.core.sec

/** A small in-memory ring of the identity steps, for the app's log page. */
object SecJournal {

    private const val MAX = 160
    private val lines = ArrayDeque<String>()

    fun note(line: String) {
        synchronized(lines) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /** Hands out everything recorded so far and clears it. */
    fun drain(): List<String> = synchronized(lines) {
        val out = lines.toList()
        lines.clear()
        out
    }
}
