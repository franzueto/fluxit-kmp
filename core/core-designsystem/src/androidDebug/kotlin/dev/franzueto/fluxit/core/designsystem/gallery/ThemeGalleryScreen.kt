package dev.franzueto.fluxit.core.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.components.FluxItBottomTabBar
import dev.franzueto.fluxit.core.designsystem.components.FluxItCard
import dev.franzueto.fluxit.core.designsystem.components.FluxItColorSwatch
import dev.franzueto.fluxit.core.designsystem.components.FluxItCompletedListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItDashboardListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItDestructiveButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItEmptyState
import dev.franzueto.fluxit.core.designsystem.components.FluxItFab
import dev.franzueto.fluxit.core.designsystem.components.FluxItIconChip
import dev.franzueto.fluxit.core.designsystem.components.FluxItInlineComposer
import dev.franzueto.fluxit.core.designsystem.components.FluxItPrimaryButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItProgressBar
import dev.franzueto.fluxit.core.designsystem.components.FluxItScaffold
import dev.franzueto.fluxit.core.designsystem.components.FluxItSearchField
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.components.FluxItTabItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItTextField
import dev.franzueto.fluxit.core.designsystem.components.FluxItToBuyListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarLarge
import dev.franzueto.fluxit.core.designsystem.icons.Account
import dev.franzueto.fluxit.core.designsystem.icons.Bell
import dev.franzueto.fluxit.core.designsystem.icons.Calendar
import dev.franzueto.fluxit.core.designsystem.icons.Cart
import dev.franzueto.fluxit.core.designsystem.icons.Check
import dev.franzueto.fluxit.core.designsystem.icons.ChevronRight
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.List
import dev.franzueto.fluxit.core.designsystem.icons.Plus
import dev.franzueto.fluxit.core.designsystem.icons.Search
import dev.franzueto.fluxit.core.designsystem.icons.Star
import dev.franzueto.fluxit.core.designsystem.icons.Trash
import dev.franzueto.fluxit.core.designsystem.theme.FluxItTheme
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItShapes
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

// Debug-only theme gallery. Lives in the androidDebug source set so release
// builds don't pull this surface in. See plan/02_DESIGN_SYSTEM.md §9.
// Snapshot test (one golden per platform) is deferred to Phase 14.

@Composable
@Suppress("ktlint:standard:function-naming")
public fun ThemeGalleryScreen() {
    FluxItTheme {
        FluxItScaffold(
            topBar = { FluxItTopBarLarge(title = "Theme Gallery") },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = FluxItSpacing.containerPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                ColorTokensSection()
                TypographySection()
                ShapesSection()
                SpacingSection()
                PrimitivesSection()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SectionTitle(label: String) {
    FluxItSectionHeader(label = label)
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun ColorTokensSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Colors")
        val colorSwatches: List<Pair<String, Color>> =
            listOf(
                "backgroundDark" to FluxItColors.backgroundDark,
                "surfaceCard" to FluxItColors.surfaceCard,
                "surfaceCardMuted" to FluxItColors.surfaceCardMuted,
                "surfaceSearch" to FluxItColors.surfaceSearch,
                "textPrimary" to FluxItColors.textPrimary,
                "textMuted" to FluxItColors.textMuted,
                "primaryBlue" to FluxItColors.primaryBlue,
                "primaryBlueShadow" to FluxItColors.primaryBlueShadow,
                "dividerSubtle" to FluxItColors.dividerSubtle,
                "accentRose" to FluxItColors.accentRose,
            )
        colorSwatches.forEach { (name, color) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color),
                )
                Text(name, style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TypographySection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Typography")
        val styles: List<Pair<String, TextStyle>> =
            listOf(
                "displayLg" to FluxItTypography.displayLg,
                "titleMd" to FluxItTypography.titleMd,
                "bodyMd" to FluxItTypography.bodyMd,
                "labelSm" to FluxItTypography.labelSm,
                "captionXs" to FluxItTypography.captionXs,
            )
        styles.forEach { (name, style) ->
            Column {
                Text(name, style = FluxItTypography.captionXs, color = FluxItColors.textMuted)
                Text("The quick brown fox.", style = style, color = FluxItColors.textPrimary)
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun ShapesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Shapes")
        val shapes: List<Pair<String, Shape>> =
            listOf(
                "sm" to FluxItShapes.sm,
                "default" to FluxItShapes.default,
                "md" to FluxItShapes.md,
                "lg" to FluxItShapes.lg,
                "xl" to FluxItShapes.xl,
                "full" to FluxItShapes.full,
            )
        shapes.forEach { (name, shape) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(shape)
                            .background(FluxItColors.surfaceCard),
                )
                Text(name, style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SpacingSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Spacing scale")
        val scale: List<Pair<String, Dp>> =
            listOf(
                "xs (4dp)" to 4.dp,
                "sm (8dp)" to 8.dp,
                "md (12dp)" to 12.dp,
                "lg (16dp)" to 16.dp,
                "xl (24dp)" to 24.dp,
                "2xl (32dp)" to 32.dp,
                "3xl (48dp)" to 48.dp,
            )
        scale.forEach { (name, dp) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier =
                        Modifier
                            .width(dp)
                            .height(8.dp)
                            .background(FluxItColors.primaryBlue),
                )
                Text(name, style = FluxItTypography.labelSm, color = FluxItColors.textMuted)
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PrimitivesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Primitives")

        // Search + composer
        var search by rememberSaveable { mutableStateOf("") }
        FluxItSearchField(value = search, onValueChange = { search = it }, searchIcon = FluxItIcons.Search)
        var composer by rememberSaveable { mutableStateOf("") }
        FluxItInlineComposer(
            value = composer,
            onValueChange = { composer = it },
            onSubmit = { composer = "" },
            submitIcon = FluxItIcons.Plus,
        )

        // Text field (single + multi)
        var single by rememberSaveable { mutableStateOf("") }
        FluxItTextField(value = single, onValueChange = { single = it }, label = "Title", placeholder = "Single-line")
        var multi by rememberSaveable { mutableStateOf("") }
        FluxItTextField(
            value = multi,
            onValueChange = { multi = it },
            label = "Description",
            placeholder = "Multi-line",
            singleLine = false,
            minLines = 3,
        )

        // Buttons
        FluxItPrimaryButton(label = "Primary action", onClick = {})
        FluxItPrimaryButton(label = "Disabled", onClick = {}, enabled = false)
        FluxItDestructiveButton(label = "Delete", trashIcon = FluxItIcons.Trash, onClick = {})

        // Card + section header
        FluxItCard {
            Text("FluxItCard (default)", style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
        }
        FluxItCard(resting = true) {
            Text("FluxItCard (resting)", style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
        }

        // List items (3 variants)
        FluxItDashboardListItem(
            icon = FluxItIcons.Cart,
            iconTint = FluxItColors.primaryBlue,
            title = "Groceries",
            subtitle = "5 items",
            trashIcon = FluxItIcons.Trash,
            onDelete = {},
            chevronIcon = FluxItIcons.ChevronRight,
        )
        FluxItToBuyListItem(title = "Avocados", onToggle = {}, trailingIcon = FluxItIcons.ChevronRight)
        FluxItCompletedListItem(
            title = "Bread",
            onToggle = {},
            checkIcon = FluxItIcons.Check,
            trashIcon = FluxItIcons.Trash,
            onDelete = {},
        )

        // Progress bar
        FluxItProgressBar(progress = 0.65f)

        // Pickers
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FluxItIconChip(icon = FluxItIcons.Cart, tint = FluxItColors.primaryBlue, selected = true, onClick = {})
            FluxItIconChip(icon = FluxItIcons.Star, tint = FluxItColors.accentRose, selected = false, onClick = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FluxItColorSwatch(color = FluxItColors.primaryBlue, selected = true, onClick = {})
            FluxItColorSwatch(color = FluxItColors.accentRose, selected = false, onClick = {})
            FluxItColorSwatch(color = FluxItColors.textMuted, selected = false, onClick = {})
        }

        // FAB (free-floating sample)
        Row {
            FluxItFab(icon = FluxItIcons.Plus, onClick = {}, contentDescription = "Create")
        }

        // Tab bar
        val tabs =
            listOf(
                FluxItTabItem(icon = FluxItIcons.List, label = "Lists"),
                FluxItTabItem(icon = FluxItIcons.Calendar, label = "Calendar"),
                FluxItTabItem(icon = FluxItIcons.Bell, label = "Reminders"),
                FluxItTabItem(icon = FluxItIcons.Account, label = "Account"),
            )
        var selected by remember { mutableStateOf(0) }
        FluxItBottomTabBar(tabs = tabs, selectedIndex = selected, onSelect = { selected = it })

        // Empty state
        FluxItEmptyState(
            icon = FluxItIcons.Search,
            title = "Nothing here yet",
            message = "Create your first list to get started.",
        )

        Spacer(Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FluxItColors.dividerSubtle))
    }
}
