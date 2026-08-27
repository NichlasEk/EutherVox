package se.euther.euthervox.app

internal object ConversationRecoveryPolicy {
    fun keepRequestedAfterFailure(recoverable: Boolean, requested: Boolean): Boolean =
        recoverable && requested

    fun shouldArm(
        ready: Boolean,
        requested: Boolean,
        utteranceActive: Boolean,
        pendingAction: Boolean,
    ): Boolean = ready && requested && !utteranceActive && !pendingAction
}
