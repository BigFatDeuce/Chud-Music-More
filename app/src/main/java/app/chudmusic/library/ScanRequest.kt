package app.chudmusic.library

/**
 * One library scan to run in the background (see [ScanWorker]): the phone, the network places
 * (all of them, or only [onlyId]), and whether network places are read even on a metered network.
 *
 * Requests are queued one after the other, never run side by side. A new request is dropped when
 * one still waiting in the queue already does at least as much ([coveredBy]): opening the app ten
 * times, or a burst of MediaStore changes, leaves one scan waiting, not ten.
 */
data class ScanRequest(
    val local: Boolean,
    val network: Boolean,
    val onlyId: Long? = null,
    val force: Boolean = false,
) {
    /** Whether [other], not started yet, will already read everything this request asks for. */
    fun coveredBy(other: ScanRequest): Boolean =
        (!local || other.local) &&
            (!network || (other.network && (other.onlyId == null || other.onlyId == onlyId) && (other.force || !force)))

    /** WorkManager tags: they are how a waiting request is read back (WorkInfo has no input data). */
    fun tags(): Set<String> = buildSet {
        add(TAG)
        if (local) add("$TAG:local")
        if (network) add("$TAG:network")
        if (force) add("$TAG:force")
        onlyId?.let { add("$TAG:only:$it") }
    }

    companion object {
        const val TAG = "library-scan"
        const val WORK = "library-scan"

        fun fromTags(tags: Set<String>): ScanRequest = ScanRequest(
            local = "$TAG:local" in tags,
            network = "$TAG:network" in tags,
            onlyId = tags.firstNotNullOfOrNull { it.removePrefix("$TAG:only:").takeIf { s -> s != it }?.toLongOrNull() },
            force = "$TAG:force" in tags,
        )

        /** Whether [new] must be queued, given the requests already [waiting] (enqueued, not running). */
        fun mustQueue(new: ScanRequest, waiting: List<ScanRequest>): Boolean =
            (new.local || new.network) && waiting.none { new.coveredBy(it) }
    }
}
