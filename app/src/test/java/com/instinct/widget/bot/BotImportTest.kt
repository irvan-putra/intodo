package com.instinct.widget.bot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitInterruptedContractTest {
    @Test
    fun commitInterruptedPrefixIsRecognized() {
        assertTrue(isCommitInterrupted(
            "COMMIT_INTERRUPTED: the import passed its decision point but did not finish (boom)."))
    }

    @Test
    fun ordinaryCommitFailuresAreNotCommitInterrupted() {
        assertFalse(isCommitInterrupted("some rpc error"))
        assertFalse(isCommitInterrupted(""))
        assertFalse(isCommitInterrupted("COMMIT_INTERRUPTED without colon"))
    }
}

class RecoveryGateLockTest {
    @Test
    fun lockControlsUntilRecoveryFailsTheGateClosed() {
        BotImport.resetRecoveryGateForTest()
        assertTrue(BotImport.isRecoveryComplete())
        BotImport.lockControlsUntilRecovery()
        // Every restore-error path locks the gate: no control may run
        // while settings sit half-written until restart recovery.
        assertFalse(BotImport.isRecoveryComplete())
        BotImport.resetRecoveryGateForTest()
    }
}

class CallerFailureBranchLockTest {
    @Test
    fun restoreErrorAfterSuccessfulAbortLocksTheGate() {
        // The exact decision every abort-succeeded caller branch makes:
        // a failed restore means partial settings, so the gate LOCKS.
        BotImport.resetRecoveryGateForTest()
        val msg = BotImport.messageAfterAbort("base failure", "io error")
        assertFalse(BotImport.isRecoveryComplete())
        assertTrue(msg.contains("restart the app"))
        BotImport.resetRecoveryGateForTest()
    }

    @Test
    fun cleanRestoreAfterSuccessfulAbortKeepsTheGateOpen() {
        BotImport.resetRecoveryGateForTest()
        val msg = BotImport.messageAfterAbort("base failure", null)
        assertTrue(BotImport.isRecoveryComplete())
        assertTrue(msg.contains("rolled back"))
    }
}
