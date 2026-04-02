package party.qwer.iris.reply

import party.qwer.iris.IrisLogger

internal class ReplyLaneJobProcessor(
    private val dispatchScheduler: DispatchScheduler,
    private val statusTracker: ReplyStatusTracker,
) {
    suspend fun process(job: ReplyLaneJob) {
        try {
            statusTracker.transition(job.requestId, ReplyTransitionEvent.PrepareStarted)
            job.prepare()
            statusTracker.transition(job.requestId, ReplyTransitionEvent.PrepareCompleted)
            dispatchScheduler.awaitPermit()
            statusTracker.transition(job.requestId, ReplyTransitionEvent.SendStarted)
            job.send()
            statusTracker.transition(job.requestId, ReplyTransitionEvent.SendCompleted)
        } catch (e: Exception) {
            statusTracker.transition(
                job.requestId,
                ReplyTransitionEvent.Failed(e.message ?: "reply pipeline failure"),
            )
            IrisLogger.error("[ReplyService] pipeline send error: ${e.message}", e)
        }
    }
}
