package com.linuxrm.IME
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
private val LightColorScheme = lightColorScheme()
private val DarkColorScheme = darkColorScheme()
object AppDimens {
	val pageHorizontalPadding = 16.dp
	val itemSpacing = 2.dp
	val groupSpacing = 24.dp
	val itemVerticalPadding = 14.dp
	val itemHorizontalPadding = 16.dp
	val titlePaddingTop = 0.dp
	val titlePaddingBottom = 0.dp
}
fun Modifier.screenPadding() : Modifier = this.padding(horizontal = AppDimens.pageHorizontalPadding, 0.dp)
@Composable fun SpacerItem() = Spacer(modifier = Modifier.height(AppDimens.itemSpacing))
@Composable fun SpacerGroup() = Spacer(modifier = Modifier.height(AppDimens.groupSpacing))
@Composable fun ExampleTheme(darkTheme : Boolean = isSystemInDarkTheme(), content : @Composable () -> Unit) {
	val context = LocalContext.current
	val baseScheme = when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			if (darkTheme) dynamicDarkColorScheme(context)
			else dynamicLightColorScheme(context)
		}
		else -> {
			if (darkTheme) DarkColorScheme else LightColorScheme
		}
	}
	val colorScheme = baseScheme.copy(background = baseScheme.surfaceContainer)
	MaterialTheme(colorScheme = colorScheme, content = content)
}
/**
 * Position of a row within a grouped list (the Android-15 settings pattern): a
 * run of rows shares one rounded container, full corners at the group's outer
 * edges and small corners between, separated by small gaps. The family's
 * canonical grouped-row primitive.
 */
enum class Position { Top, Middle, Bottom, Alone }
fun positionOf(index : Int, count : Int) : Position = when {
	count <= 1 -> Position.Alone
	index == 0 -> Position.Top
	index == count - 1 -> Position.Bottom
	else -> Position.Middle
}
/**
 * The inset a grouped list keeps from the screen edge.
 *
 * Exported because it is a *shared* line, not a private detail of [GroupedRow]:
 * everything that sits outside the cards but belongs to them — section headers,
 * hints, picker descriptions — has to start on the same edge, or the page reads
 * as two ragged columns. Take this value rather than writing 16.dp again.
 */
val GroupedListInset = 0.dp
/** Corner radii for a grouped segment: full at the group's outer edges, small between. */
fun groupedShape(position : Position, full : Dp, small : Dp) : Shape = when (position) {
	Position.Alone -> RoundedCornerShape(full)
	Position.Top -> RoundedCornerShape(topStart = full, topEnd = full, bottomStart = small, bottomEnd = small)
	Position.Middle -> RoundedCornerShape(small)
	Position.Bottom -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = full, bottomEnd = full)
}
/**
 * One segment of a grouped list: a tonal [Surface] whose corner radii come from
 * [position] (so a run reads as a single rounded card), with a small gap below
 * all but the last. Corners morph rounder on press — the expressive shape play.
 * The caller supplies [content] and any horizontal inset via [modifier], so the
 * same primitive serves both the 16dp-inset lists and full-width detail cards.
 *
 * Set [gapBelow] to false to suppress that 2dp separation — for a caller that
 * owns uniform spacing itself, e.g. [ReorderableColumn], where every slot must
 * share the same pitch for the drag maths to line up.
 *
 * [onLongClick] adds a long-press to the same surface (with the platform's
 * long-press haptic), for lists that enter a selection mode from a row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class) @Composable fun GroupedSurface(
	position : Position,
	modifier : Modifier = Modifier,
	onClick : (() -> Unit)? = null,
	onLongClick : (() -> Unit)? = null,
	color : Color = MaterialTheme.colorScheme.surfaceContainerHigh,
	gapBelow : Boolean = true,
	content : @Composable () -> Unit,
) {
	val interaction = remember { MutableInteractionSource() }
	val pressed by interaction.collectIsPressedAsState()
	val full by animateDpAsState(if (pressed) 36.dp else 22.dp, label = "fullCorner")
	val small by animateDpAsState(if (pressed) 36.dp else 3.dp, label = "smallCorner")
	val shape = groupedShape(position, full, small)
	val gap = when {
		! gapBelow -> Modifier
		position == Position.Top || position == Position.Middle -> Modifier.padding(bottom = 2.dp)
		else -> Modifier
	}
	val base = modifier
		.fillMaxWidth()
		.then(gap)
	val haptics = LocalHapticFeedback.current
	when {
		onLongClick != null -> Surface(
			color = color,
			shape = shape,
			modifier = base.combinedClickable(
				interactionSource = interaction,
				indication = ripple(),
				onClick = { onClick?.invoke() },
				onLongClick = {
					haptics.performHapticFeedback(HapticFeedbackType.LongPress)
					onLongClick()
				},
			),
		) { content() }
		onClick != null -> Surface(onClick = onClick, color = color, shape = shape, interactionSource = interaction, modifier = base) { content() }
		else -> Surface(color = color, shape = shape, modifier = base) { content() }
	}
}
/**
 * One row in a grouped list: an M3 [ListItem] over a [GroupedSurface] (the
 * tonal, position-shaped, press-morphing family container). Insets 16dp like
 * the lists overview.
 *
 * [selected] highlights the row in the secondary container. [dimmed] fades the
 * headline and summary to the M3 disabled emphasis while leaving the [trailing]
 * control at full opacity — for rows that are present but switched off.
 * [container] overrides the row's tonal color (e.g. a category tint); ignored
 * when [selected]. [gapBelow] (see [GroupedSurface]) suppresses the inter-row
 * gap for a caller that owns its own uniform spacing, e.g. [ReorderableColumn].
 * [onLongClick] (see [GroupedSurface]) adds a long-press to the row.
 */
@Composable fun GroupedRow(
	title : String,
	position : Position,
	modifier : Modifier = Modifier,
	summary : String? = null,
	selected : Boolean = false,
	dimmed : Boolean = false,
	container : Color? = null,
	minHeight : Dp = 36.dp,
	gapBelow : Boolean = true,
	leading : @Composable (() -> Unit)? = null,
	trailing : @Composable (() -> Unit)? = null,
	onClick : (() -> Unit)? = null,
	onLongClick : (() -> Unit)? = null,
) = GroupedRow(
	title = AnnotatedString(title),
	position = position,
	modifier = modifier,
	summary = summary?.let { AnnotatedString(it) },
	selected = selected,
	dimmed = dimmed,
	container = container,
	minHeight = minHeight,
	gapBelow = gapBelow,
	leading = leading,
	trailing = trailing,
	onClick = onClick,
	onLongClick = onLongClick,
)
/**
 * [GroupedRow] with styled text, for a row whose title or summary carries its own
 * emphasis — a search hit marking the run that matched, say. Identical otherwise;
 * the plain-[String] overload delegates here.
 */
@OptIn(ExperimentalMaterial3Api::class) @Composable fun GroupedRow(
	title : AnnotatedString,
	position : Position,
	modifier : Modifier = Modifier,
	summary : AnnotatedString? = null,
	selected : Boolean = false,
	dimmed : Boolean = false,
	container : Color? = null,
	minHeight : Dp = 72.dp,
	gapBelow : Boolean = true,
	leading : @Composable (() -> Unit)? = null,
	trailing : @Composable (() -> Unit)? = null,
	onClick : (() -> Unit)? = null,
	onLongClick : (() -> Unit)? = null,
) {
	val itemColors = if (selected) {
		ListItemDefaults.colors(
			containerColor = Color.Transparent,
			headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
			leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
			supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
			trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
		)
	} else if (dimmed) {
		val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
		ListItemDefaults.colors(
			containerColor = Color.Transparent,
			headlineColor = muted,
			leadingIconColor = muted,
			supportingColor = muted,
		)
	} else {
		ListItemDefaults.colors(containerColor = Color.Transparent)
	}
	val containerColor = when {
		selected -> MaterialTheme.colorScheme.secondaryContainer
		container != null -> container
		else -> MaterialTheme.colorScheme.surfaceBright
	}
	GroupedSurface(
		position = position,
		modifier = modifier.padding(horizontal = GroupedListInset),
		onClick = onClick,
		onLongClick = onLongClick,
		color = containerColor,
		gapBelow = gapBelow,
	) {
		ListItem(
			headlineContent = { Text(title) },
			supportingContent = summary?.let { text -> { Text(text) } },
			leadingContent = leading,
			trailingContent = trailing,
			colors = itemColors,
			modifier = Modifier.heightIn(min = minHeight),
		)
	}
}
@OptIn(ExperimentalMaterial3Api::class) @Composable fun AppScaffold(title : String, showBack : Boolean = false, onBack : (() -> Unit)? = null, content : @Composable (PaddingValues) -> Unit) {
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
	val context = LocalContext.current
	val layoutDirection = LocalLayoutDirection.current
	Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = @Composable {
		LargeTopAppBar(title = { Text(title) }, scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), navigationIcon = {
			if (showBack) {
				IconButton(onClick = { onBack?.invoke() ?: (context as? ComponentActivity)?.finish() }) {
					Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
				}
			}
		})
	}) { paddingValues ->
		val modifiedPadding = PaddingValues(start = paddingValues.calculateLeftPadding(layoutDirection), top = paddingValues.calculateTopPadding(), end = paddingValues.calculateRightPadding(layoutDirection), bottom = 0.dp)
		content(modifiedPadding)
	}
}
