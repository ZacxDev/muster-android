package dev.zacx.muster.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The switcher in the app bar (operator decision 2): it shows the server in
 * view; a tap opens the menu of servers, picking another switches; a long
 * press opens server management; Add server starts the add flow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class TenantBarTest {
    @get:Rule
    val rule = createComposeRule()

    private val home = TenantRecord(Tenants.DEFAULT, "home.example.test", Tenants.PALETTE[0], Tenants.DEFAULT_PROFILE,
        DeviceRecord(origin = "https://home.example.test", token = "mdv_" + "T".repeat(43), deviceId = 1))
    private val work = TenantRecord("t2", "work.example.test", Tenants.PALETTE[1], "tenant-t2", DeviceRecord(origin = "https://work.example.test"))

    private val events = mutableListOf<String>()

    private fun show(current: String) {
        rule.setContent {
            MaterialTheme {
                TenantBar(
                    Tenants(current = current, all = listOf(home, work), migrated = true),
                    onSelect = { events += "select:$it" },
                    onManage = { events += "manage" },
                    onAdd = { events += "add" },
                )
            }
        }
    }

    @Test
    fun theBarShowsTheServerInView() {
        show(current = "t2")
        rule.onNodeWithTag(TAG_PICKER).assertIsDisplayed()
        rule.onNodeWithText("work.example.test").assertIsDisplayed()
        rule.onNodeWithText("home.example.test").assertDoesNotExist()
    }

    @Test
    fun pickingAnotherServerSwitchesToIt() {
        show(current = Tenants.DEFAULT)
        rule.onNodeWithTag(TAG_PICKER).performClick()
        rule.onNodeWithTag(tagTenant("t2")).assertIsDisplayed().performClick()
        assertEquals(listOf("select:t2"), events)
    }

    /** Picking the server already in view is not a switch. */
    @Test
    fun pickingTheServerInViewDoesNothing() {
        show(current = Tenants.DEFAULT)
        rule.onNodeWithTag(TAG_PICKER).performClick()
        rule.onNodeWithTag(tagTenant(Tenants.DEFAULT)).performClick()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aLongPressOpensServerManagement() {
        show(current = Tenants.DEFAULT)
        rule.onNodeWithTag(TAG_PICKER).performTouchInput { longClick() }
        assertEquals(listOf("manage"), events)
        rule.onNodeWithTag(tagTenant("t2")).assertDoesNotExist() // the long press did not open the menu
    }

    @Test
    fun addServerStartsTheAddFlow() {
        show(current = Tenants.DEFAULT)
        rule.onNodeWithTag(TAG_PICKER).performClick()
        rule.onNodeWithTag(TAG_ADD).performClick()
        assertEquals(listOf("add"), events)
    }

    /** Server management: Remove asks first, and removes only the server it was pressed on. */
    @Test
    fun removingAServerAsksFirst() {
        rule.setContent {
            MaterialTheme {
                ServersScreen(
                    Tenants(current = Tenants.DEFAULT, all = listOf(home, work), migrated = true),
                    onOpen = { events += "open:$it" }, onSettings = { events += "settings:$it" }, onRemove = { events += "remove:$it" },
                    onAdd = { events += "add" }, onClose = {},
                )
            }
        }
        rule.onNodeWithTag("remove-t2").performClick()
        assertEquals("nothing removed before the confirmation", emptyList<String>(), events)
        rule.onNodeWithText("Remove work.example.test?").assertIsDisplayed()
        rule.onNodeWithTag(TAG_REMOVE_CONFIRM).performClick()
        assertEquals(listOf("remove:t2"), events)
    }

    @Test
    fun manageServersIsInTheMenuToo() {
        show(current = Tenants.DEFAULT)
        rule.onNodeWithTag(TAG_PICKER).performClick()
        rule.onNodeWithTag(TAG_MANAGE).performClick()
        assertEquals(listOf("manage"), events)
    }
}
