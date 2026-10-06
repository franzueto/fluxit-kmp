package com.fluxit

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.ui.components.operationErrorActionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationErrorFeedbackTest {

    @Test
    fun retryableFailureShowsEnabledRetryAndDismissWhenIdle() {
        val actions = operationErrorActionState(
            RepositoryErrorCode.OFFLINE.toApplicationError(),
            operationInFlight = false,
        )

        assertTrue(actions.showRetry)
        assertTrue(actions.retryEnabled)
        assertTrue(actions.dismissEnabled)
    }

    @Test
    fun retryableFailureDisablesBothActionsWhileRetryIsRunning() {
        val actions = operationErrorActionState(
            RepositoryErrorCode.TIMEOUT.toApplicationError(),
            operationInFlight = true,
        )

        assertTrue(actions.showRetry)
        assertFalse(actions.retryEnabled)
        assertFalse(actions.dismissEnabled)
    }

    @Test
    fun permanentFailureOffersDismissWithoutRetry() {
        val actions = operationErrorActionState(
            RepositoryErrorCode.FORBIDDEN.toApplicationError(),
            operationInFlight = false,
        )

        assertFalse(actions.showRetry)
        assertFalse(actions.retryEnabled)
        assertTrue(actions.dismissEnabled)
    }
}
