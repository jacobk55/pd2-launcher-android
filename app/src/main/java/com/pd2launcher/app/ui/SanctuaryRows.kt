package com.pd2launcher.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pd2launcher.app.soe.SanctuaryOfExile
import com.pd2launcher.app.soe.SanctuaryOfExile.Mode
import com.pd2launcher.app.soe.SanctuaryOfExile.Work

/**
 * Settings → Game → Mods: which mod (Sanctuary of Exile Standard or Damnation, or Single Player+),
 * the recommended loot filter, Install (or Update), and Restore Project Diablo 2 ([SanctuaryOfExile]).
 */
@Composable
fun ColumnScope.SanctuaryRows() {
    val installed by SanctuaryOfExile.installed.collectAsState()
    val latest by SanctuaryOfExile.latest.collectAsState()
    val work by SanctuaryOfExile.work.collectAsState()
    var mode by remember(installed) { mutableStateOf(installed?.mode ?: Mode.STANDARD) }
    var filter by remember(installed) { mutableStateOf(installed?.filter ?: true) }
    LaunchedEffect(Unit) { SanctuaryOfExile.refreshSoon() }
    LaunchedEffect(mode) { SanctuaryOfExile.checkLatest(mode) }
    val running = work is Work.Running
    val latestTag = latest[mode.repo]

    val status = when (val current = installed) {
        null -> "No mod installed: Project Diablo 2 plays as normal."
        else -> "Installed: ${current.mode.title} ${current.version}" +
            (if (current.mode.isSoe) " (${current.mode.label})" else "") + ". Project Diablo 2's own updates are paused."
    }
    val newest = latestTag?.let { "Newest ${mode.title} release: $it" }.orEmpty()
    Text(status, style = Type.Body, color = if (installed != null) Palette.Teal else Palette.Text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    if (newest.isNotEmpty()) Text(newest, style = Type.Hint, color = Palette.Muted, modifier = Modifier.padding(horizontal = 14.dp))
    Rule(Modifier.padding(horizontal = 14.dp))
    OptionRow(
        "Sanctuary of Exile: Standard", "The mod as most people play it. It may lag behind the newest Project Diablo 2 season.",
        mode == Mode.STANDARD, { mode = Mode.STANDARD; filter = true }, !running,
    )
    Rule(Modifier.padding(horizontal = 14.dp))
    OptionRow(
        "Sanctuary of Exile: Damnation", "The harder mode. Characters of one mode are not meant for the other.",
        mode == Mode.DAMNATION, { mode = Mode.DAMNATION; filter = true }, !running,
    )
    Rule(Modifier.padding(horizontal = 14.dp))
    OptionRow(
        "Single Player+", "Faster progression for playing alone: better drops, orbs, crafting. Updated for the newest season.",
        mode == Mode.SINGLE_PLAYER_PLUS, { mode = Mode.SINGLE_PLAYER_PLUS; filter = true }, !running,
    )
    Rule(Modifier.padding(horizontal = 14.dp))
    ToggleRow(
        "Recommended loot filter",
        "The loot filter for the chosen mod as the game's loot filter: HiimFilter's for Sanctuary of Exile, Roofoo's for Single Player+. A filter that does not know a mod's newer items can leave them unlabeled, and the game can crash when you point at one: turn this off to keep your own.",
        filter, { filter = it }, !running,
    )
    Rule(Modifier.padding(horizontal = 14.dp))

    val update = installed != null && installed?.mode?.repo == mode.repo && latestTag != null && latestTag != installed?.version
    val action = when {
        installed == null -> "Install ${mode.title}"
        installed?.mode?.repo != mode.repo -> "Switch to ${mode.title}"
        update -> "Update to $latestTag"
        installed?.mode != mode || installed?.filter != filter -> "Apply changes"
        else -> "Reinstall"
    }
    FlowRow(
        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LauncherButton(action, { SanctuaryOfExile.install(mode, filter) }, kind = Kind.PRIMARY, enabled = !running, height = 48.dp, padding = 16.dp)
        if (installed != null) {
            LauncherButton("Restore Project Diablo 2", { SanctuaryOfExile.restore() }, kind = Kind.DANGER, enabled = !running, height = 48.dp, padding = 16.dp)
        }
    }
    when (val current = work) {
        is Work.Running -> Banner(
            current.step + (current.percent?.let { " $it%" } ?: ""), Palette.Amber,
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        is Work.Done -> Banner(current.message, Palette.Teal, Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        is Work.Failed -> Banner(current.message, Palette.Red, Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        Work.Idle -> Text(
            "Each mod needs its own new characters: start with an empty Save folder. Keep updates off in the official PD2 launcher, which would put Project Diablo 2's files back.",
            style = Type.Hint, color = Palette.Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}
