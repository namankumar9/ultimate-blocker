package neth.iecal.curbox.api

/** Kept only in memory: recreating the warning screen requires a fresh check. */
internal class ExternalUnlockAttempt {
    private var requestId: String? = null
    private var startedAtMs = 0L
    private var authorizationRevision = ""

    fun start(id: String, nowMs: Long, revision: String = "") {
        requestId = id
        startedAtMs = nowMs
        authorizationRevision = revision
    }

    fun clear() {
        requestId = null
    }

    fun consume(returnedId: String?, approved: Boolean, nowMs: Long, revision: String = ""): Boolean {
        val expected = requestId
        clear()
        return expected != null && expected == returnedId && approved && authorizationRevision == revision &&
            nowMs - startedAtMs in 0..600_000L
    }
}
