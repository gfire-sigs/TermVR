package dev.gfire.termvr.home

import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.ui.home.HomeUiState
import dev.gfire.termvr.ui.home.HomeViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeViewModelReadinessTest {

    @Test
    fun `HomeUiState warns when running containers exceed recommended 3-sandbox 32-process cap`() {
        val state = HomeUiState(
            runStates = mapOf(
                "c1" to SandboxRunState.RUNNING,
                "c2" to SandboxRunState.RUNNING,
                "c3" to SandboxRunState.RUNNING,
                "c4" to SandboxRunState.RUNNING,
            ),
            hostReadiness = HostReadinessReport(
                hostSdkInt = 35,
                hostAbi = "arm64-v8a",
                liveChildProcessCount = 12,
                phantomKillerDetected = false,
                remediationKind = PhantomProcessRemediation.NONE,
            ),
        )

        assertTrue(state.hasReadinessWarning)
        assertNotNull(state.readinessWarningMessage)
        assertTrue(state.readinessWarningMessage!!.contains("4 containers"))
        assertTrue(state.readinessWarningMessage!!.contains("12/32"))
        assertEquals(
            "4 running · 12/32 procs",
            HomeViewModel.formatRunningStatusSummary(state.runningCount, state.hostReadiness.liveChildProcessCount),
        )
    }

    @Test
    fun `HomeUiState formats Android 14+ and Android 12-13 SIGKILL phantom-killer warnings and highlights error notices`() {
        val sdk34Killed = HomeUiState(
            runStates = mapOf("c1" to SandboxRunState.FAILED),
            hostReadiness = HostReadinessReport(
                hostSdkInt = 34,
                hostAbi = "arm64-v8a",
                liveChildProcessCount = 0,
                phantomKillerDetected = true,
                remediationKind = PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE,
            ),
            serviceNotice = "Container 'Debian' stopped on SIGKILL (exit 137) from Android process or memory limits.",
        )
        assertTrue(sdk34Killed.hasReadinessWarning)
        assertTrue(sdk34Killed.isNoticeError)
        assertTrue(sdk34Killed.readinessWarningMessage.orEmpty().contains("Disable child process restrictions"))

        val sdk32Killed = HomeUiState(
            hostReadiness = HostReadinessReport(
                hostSdkInt = 32,
                hostAbi = "arm64-v8a",
                phantomKillerDetected = true,
                remediationKind = PhantomProcessRemediation.ANDROID_12_13_ADB_COMMAND,
            ),
        )
        assertTrue(sdk32Killed.readinessWarningMessage.orEmpty().contains("32-process"))

        val healthy = HomeUiState(
            runStates = mapOf("c1" to SandboxRunState.RUNNING, "c2" to SandboxRunState.RUNNING),
            hostReadiness = HostReadinessReport(
                hostSdkInt = 35,
                hostAbi = "arm64-v8a",
                liveChildProcessCount = 8,
            ),
            serviceNotice = "Saved checkpoint for 'Debian' was superseded after engine update; starting cold boot.",
        )
        assertFalse(healthy.hasReadinessWarning)
        assertNull(healthy.readinessWarningMessage)
        assertFalse(healthy.isNoticeError)
        assertEquals("2 running · 8/32 procs", HomeViewModel.formatRunningStatusSummary(2, 8))
        assertEquals("1 running", HomeViewModel.formatRunningStatusSummary(1, 0))
    }
}
