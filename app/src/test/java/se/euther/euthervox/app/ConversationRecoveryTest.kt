package se.euther.euthervox.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecoveryPolicyTest {
    @Test
    fun recoverableFailureKeepsExplicitConversationRequest() {
        assertTrue(ConversationRecoveryPolicy.keepRequestedAfterFailure(recoverable = true, requested = true))
        assertFalse(ConversationRecoveryPolicy.keepRequestedAfterFailure(recoverable = false, requested = true))
        assertFalse(ConversationRecoveryPolicy.keepRequestedAfterFailure(recoverable = true, requested = false))
    }

    @Test
    fun rearmRequiresReadyIdleSessionWithoutPendingConfirmation() {
        assertTrue(
            ConversationRecoveryPolicy.shouldArm(
                ready = true,
                requested = true,
                utteranceActive = false,
                pendingAction = false,
            ),
        )
        assertFalse(ConversationRecoveryPolicy.shouldArm(true, true, true, false))
        assertFalse(ConversationRecoveryPolicy.shouldArm(true, true, false, true))
        assertFalse(ConversationRecoveryPolicy.shouldArm(false, true, false, false))
        assertFalse(ConversationRecoveryPolicy.shouldArm(true, false, false, false))
    }
}
