package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The palette, taken from the approved prototype.
 *
 * A file rather than inline `Color(0x…)` at each use, so "the warning colour"
 * has one definition. The status colours matter most: they carry meaning that
 * the text does not repeat, and three slightly different greens would read as
 * three different states.
 */
internal object MobileColors {
    val Brand = Color(0xFF5B4FC4)
    val BrandSoft = Color(0xFFECEAFB)
    val Ink = Color(0xFF17171A)
    val Muted = Color(0xFF70707A)
    val Line = Color(0xFFE6E6EA)
    val Surface = Color(0xFFFFFFFF)
    val Canvas = Color(0xFFF2F2F5)

    val Ok = Color(0xFF2E7D32)
    val OkSoft = Color(0xFFE6F4E8)
    val Warn = Color(0xFFB45309)
    val WarnSoft = Color(0xFFFDF1E3)
    val Err = Color(0xFFC62828)
    val ErrSoft = Color(0xFFFDEAEA)
    val Neutral = Color(0xFFB0B0B8)
    val NeutralSoft = Color(0xFFEEEEF2)
}

/** Where a status is shown as a coloured mark. */
internal enum class Tone { OK, WARN, ERR, NEUTRAL, BRAND }

internal fun Tone.color(): Color = when (this) {
    Tone.OK -> MobileColors.Ok
    Tone.WARN -> MobileColors.Warn
    Tone.ERR -> MobileColors.Err
    Tone.BRAND -> MobileColors.Brand
    Tone.NEUTRAL -> MobileColors.Neutral
}

internal fun Tone.soft(): Color = when (this) {
    Tone.OK -> MobileColors.OkSoft
    Tone.WARN -> MobileColors.WarnSoft
    Tone.ERR -> MobileColors.ErrSoft
    Tone.BRAND -> MobileColors.BrandSoft
    Tone.NEUTRAL -> MobileColors.NeutralSoft
}

/** A small filled dot. Always accompanied by text; colour alone is not a label. */
@Composable
internal fun StatusDot(tone: Tone, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(9.dp)
            .background(tone.color(), CircleShape),
    )
}

/** A short status word on a tinted background. */
@Composable
internal fun Pill(text: String, tone: Tone = Tone.NEUTRAL) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tone.color(),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(tone.soft(), RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/**
 * An inline explanation of something that is wrong or worth knowing.
 *
 * Every one of these ends with what to do next. A banner that only states the
 * problem is a banner the user has to solve themselves.
 */
@Composable
internal fun InfoBanner(title: String, body: String, tone: Tone) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.soft(), RoundedCornerShape(12.dp))
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = when (tone) {
                Tone.ERR -> "!"
                Tone.WARN -> "!"
                else -> "ℹ"
            },
            color = tone.color(),
            fontWeight = FontWeight.Bold,
        )
        Column {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = tone.color(),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = tone.color(),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The full-height state shown when a list has nothing in it, or a screen has
 * nothing to show yet.
 *
 * All four of loading, empty, disconnected and failed go through here so they
 * look like members of one family rather than four different ideas about what a
 * state is.
 */
@Composable
internal fun StateBlock(
    glyph: String,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MobileColors.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MobileColors.Muted,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** A skeleton row, for a list that is still loading. */
@Composable
internal fun SkeletonCard(lines: Int = 2) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MobileColors.Surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            repeat(lines) { index ->
                Box(
                    Modifier
                        .padding(top = if (index == 0) 0.dp else 10.dp)
                        .height(if (index == 0) 15.dp else 12.dp)
                        .fillMaxWidth(if (index == 0) 0.52f else 0.78f)
                        .background(MobileColors.NeutralSoft, RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

/** A section heading between cards. */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MobileColors.Muted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** One name/value line inside a card. */
@Composable
internal fun KeyValue(key: String, value: String, valueTone: Tone? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MobileColors.Muted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueTone?.color() ?: MobileColors.Ink,
            fontWeight = if (valueTone != null) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A card, optionally tappable. */
@Composable
internal fun MobileCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val colors = CardDefaults.cardColors(containerColor = MobileColors.Surface)
    if (onClick == null) {
        Card(modifier.fillMaxWidth(), colors = colors, shape = shape) {
            Column(Modifier.padding(14.dp)) { content() }
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            shape = shape,
        ) {
            Column(Modifier.padding(14.dp)) { content() }
        }
    }
}

/**
 * Minimum touch target for anything interactive.
 *
 * 48dp is the accessibility floor, and it applies to the tappable area rather
 * than the visible glyph — a 20dp icon inside a 48dp button is fine, a 20dp
 * button is not.
 */
internal val MinTouchTarget = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
