package dev.cybercore.nightwatch

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Back rules live on [UiState], so they're testable without Android. */
class BackTest {
    private val host = Host("h1", "blackbox", "http://blackbox.tail9311b5.ts.net:8769/")

    private fun canGoBack(s: UiState) = s.canGoBack

    @Test
    fun backReturnsToWatchBeforeLeaving() {
        assertEquals(false, canGoBack(UiState(hosts = listOf(host), tab = Tab.Overview)))
        for (tab in listOf(Tab.Units, Tab.Stacks, Tab.Alerts, Tab.More)) {
            assertEquals(tab.name, true, canGoBack(UiState(hosts = listOf(host), tab = tab)))
        }
        assertEquals(true, canGoBack(UiState(hosts = listOf(host), tab = Tab.Overview, stack = listOf(Detail.Deck))))
        // First run: the Add host page is the whole app.
        assertEquals(false, canGoBack(UiState(hosts = emptyList(), stack = listOf(Detail.AddHost))))
    }
}
