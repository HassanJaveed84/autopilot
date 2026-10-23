package com.lifeadmin.autopilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.delay

/**
 * Top row of the three main tabs: route mark + today's date on the left, Settings (with a warning
 * dot when reminder health needs attention) on the right.
 */
@Composable
fun ScreenHeader(
    dateText: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showMark: Boolean = false,
    healthDot: Boolean = false,
    /** Standalone research (M4): opens the research history; null hides the button. */
    onOpenResearch: (() -> Unit)? = null,
) {
    val c = AutopilotTheme.colors
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMark) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.inverse), contentAlignment = Alignment.Center) {
                Icon(AutopilotIcons.Route, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(dateText, style = MaterialTheme.typography.titleSmall, color = c.ink2, modifier = Modifier.weight(1f).testTag("header_date"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onOpenResearch != null) RoundIconButton(AutopilotIcons.Research, contentDescription = "Research", onClick = onOpenResearch, modifier = Modifier.testTag("nav_research"))
        Box {
            RoundIconButton(
                AutopilotIcons.Sliders,
                contentDescription = if (healthDot) "Settings. Reminder health needs attention" else "Settings",
                onClick = onOpenSettings,
                modifier = Modifier.testTag("nav_settings"),
            )
            if (healthDot) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(10.dp).clip(CircleShape).background(c.signal).border(2.dp, c.paper, CircleShape))
        }
    }
}

/** Back-arrow header for pushed screens (Settings, Health, AI planning, Task detail). */
@Composable
fun BackHeader(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    actions: @Composable () -> Unit = {},
) {
    val c = AutopilotTheme.colors
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(AutopilotIcons.Back, contentDescription = backLabel, onClick = onBack)
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(start = 4.dp).weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** Large Bricolage page title ("Inbox", "Tasks", "Settings"). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displayMedium, color = AutopilotTheme.colors.ink, modifier = modifier.semantics { heading() })
}

/**
 * The persistent capture bar above the navigation bar. It is a button, not a text field: tapping
 * opens the capture sheet with the keyboard up.
 */
@Composable
fun CaptureBar(onClick: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "What do you need to get done?") {
    val c = AutopilotTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(6.dp, CircleShape, clip = false, ambientColor = c.ink.copy(alpha = 0.25f), spotColor = c.ink.copy(alpha = 0.25f))
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.lineStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Capture. $placeholder" }
            .testTag("fab_capture")
            .height(60.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(c.inverse), contentAlignment = Alignment.Center) {
            Icon(AutopilotIcons.Plus, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(22.dp))
        }
        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Three-tab navigation: Today · Inbox (count badge) · Tasks. */
@Composable
fun FlightNavBar(
    items: List<NavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AutopilotTheme.colors
    Surface(modifier.fillMaxWidth(), color = c.surface, contentColor = c.ink) {
        Column {
            GroupDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                items.forEach { item ->
                    val current = item.route == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Tab, onClick = { onSelect(item.route) })
                            .semantics { if (current) this.contentDescription = "${item.label}, selected tab" else this.contentDescription = "${item.label} tab" }
                            .testTag("nav_${item.route}")
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(Modifier.width(60.dp).height(32.dp).clip(CircleShape).background(if (current) c.sunken else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
                            Icon(item.icon, contentDescription = null, tint = if (current) c.ink else c.ink2, modifier = Modifier.size(22.dp))
                            if (item.badge > 0) {
                                // The count is also in the tab's description, so the badge keeps a fixed
                                // dp size instead of growing with the font scale and covering the icon.
                                val badgeSize = with(LocalDensity.current) { 11.dp.toSp() }
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(end = 6.dp).heightIn(min = 18.dp).clip(CircleShape).background(c.signal).padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(item.badge.toString(), style = MaterialTheme.typography.labelSmall.copy(fontSize = badgeSize, lineHeight = badgeSize), color = c.onSignal, maxLines = 1)
                                }
                            }
                        }
                        Text(item.label, style = if (current) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall, color = if (current) c.ink else c.ink2)
                    }
                }
            }
        }
    }
}

data class NavItem(val route: String, val label: String, val icon: ImageVector, val badge: Int = 0)

/**
 * How long a toast stays up. Material 3 only offers 4 s, 10 s or forever (and defaults to forever
 * whenever there is an action label, which left "Saved to Inbox. Open" covering the screen), so
 * [AutopilotSnackbar] runs the timer itself and every caller goes through [showToast].
 */
const val TOAST_MILLIS = 5_000L

/**
 * Show a toast for [TOAST_MILLIS] (longer if an accessibility service asks for more time), with
 * an optional action. The toast can also be swiped away. Returns [SnackbarResult.ActionPerformed]
 * if the action was tapped in time, otherwise [SnackbarResult.Dismissed].
 */
suspend fun SnackbarHostState.showToast(message: String, actionLabel: String? = null): SnackbarResult =
    showSnackbar(message, actionLabel, withDismissAction = false, duration = SnackbarDuration.Indefinite)

/**
 * Toast-styled snackbar: dark pill with an orange action, announced politely to TalkBack, dismissed
 * after [TOAST_MILLIS] or by swiping it sideways.
 */
@Composable
fun AutopilotSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(data) {
        val millis = accessibility?.calculateRecommendedTimeoutMillis(
            TOAST_MILLIS, containsIcons = false, containsText = true, containsControls = data.visuals.actionLabel != null,
        ) ?: TOAST_MILLIS
        delay(millis)
        data.dismiss()
    }
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { target -> if (target != SwipeToDismissBoxValue.Settled) data.dismiss(); true },
    )
    SwipeToDismissBox(state = swipe, backgroundContent = {}, modifier = modifier.testTag("toast")) {
        Snackbar(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(18.dp),
            containerColor = c.toast,
            contentColor = c.onToast,
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = { data.performAction() }, colors = ButtonDefaults.textButtonColors(contentColor = c.toastAction)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            dismissAction = null,
        ) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
