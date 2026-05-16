package com.mythara.glasses

import android.util.Log
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.CornerRadius
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.IconStyle
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Translates a Mythara-level [GlassesScreen] into a single
 * `display.sendContent { ... }` call.
 *
 * Each screen renders ONE root view (either a `flexBox` or a `video`);
 * the DAT contract is "one sendContent replaces the previous content
 * on the glasses." Buttons + clickable flexBoxes route their `onClick`
 * back through [publish] which feeds [GlassesDatFacade.events].
 *
 * Kept as a stateless `object` — the renderer never holds the Display
 * reference; the facade passes a fresh one each call. That keeps the
 * lifecycle in one place (the facade owns the session).
 */
internal object GlassesScreenRenderer {

    private const val TAG = "Mythara/GlassesRender"

    suspend fun render(
        display: Display,
        screen: GlassesScreen,
        publish: (GlassesEvent) -> Unit,
    ) {
        val result = when (screen) {
            GlassesScreen.Root -> renderRoot(display, publish)
            is GlassesScreen.PttListening -> renderPttListening(display, screen, publish)
            is GlassesScreen.LiveTranscript -> renderLiveTranscript(display, screen, publish)
            is GlassesScreen.ProfileCard -> renderProfileCard(display, screen, publish)
            is GlassesScreen.InsightTicker -> renderInsightTicker(display, screen, publish)
            is GlassesScreen.FavoritesList -> renderFavoritesList(display, screen, publish)
            is GlassesScreen.PhotoMemoryToast -> renderPhotoMemoryToast(display, screen, publish)
            is GlassesScreen.Error -> renderError(display, screen, publish)
        }
        result.onFailure { error, _ ->
            Log.w(TAG, "sendContent failed for ${screen::class.simpleName}: ${error.description}")
        }
    }

    private suspend fun renderRoot(display: Display, publish: (GlassesEvent) -> Unit) =
        display.sendContent {
            flexBox(gap = 8, padding = 16, background = FlexBoxBackground.CARD) {
                text("Mythara", style = TextStyle.HEADING)
                text("Tap to act", style = TextStyle.BODY, color = TextColor.SECONDARY)
                button(
                    label = "Talk",
                    style = ButtonStyle.PRIMARY,
                    iconName = IconName.SPEECH_BUBBLE,
                    onClick = { publish(GlassesEvent.PttStart) },
                )
                button(
                    label = "Photo",
                    style = ButtonStyle.SECONDARY,
                    iconName = IconName.VIDEO_CAMERA,
                    onClick = { publish(GlassesEvent.PhotoCapture) },
                )
                button(
                    label = "Recognize",
                    style = ButtonStyle.SECONDARY,
                    iconName = IconName.PERSON,
                    onClick = { publish(GlassesEvent.RecognizePerson) },
                )
                button(
                    label = "Favorites",
                    style = ButtonStyle.SECONDARY,
                    iconName = IconName.STAR,
                    onClick = { publish(GlassesEvent.OpenFavorites) },
                )
                button(
                    label = "Tone",
                    style = ButtonStyle.SECONDARY,
                    iconName = IconName.MUSIC_NOTE,
                    onClick = { publish(GlassesEvent.ToggleToneMode) },
                )
            }
        }

    private suspend fun renderPttListening(
        display: Display,
        screen: GlassesScreen.PttListening,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(gap = 12, padding = 20, background = FlexBoxBackground.CARD) {
            icon(name = IconName.SPEECH_BUBBLE, style = IconStyle.FILLED)
            text("Listening…", style = TextStyle.HEADING)
            if (screen.partial.isNotBlank()) {
                text(screen.partial, style = TextStyle.BODY, color = TextColor.SECONDARY)
            }
            button(
                label = "Stop",
                style = ButtonStyle.PRIMARY,
                iconName = IconName.X,
                onClick = { publish(GlassesEvent.PttStop) },
            )
        }
    }

    private suspend fun renderLiveTranscript(
        display: Display,
        screen: GlassesScreen.LiveTranscript,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(gap = 8, padding = 20, background = FlexBoxBackground.CARD) {
            if (!screen.final.isNullOrBlank()) {
                text(screen.final, style = TextStyle.BODY)
            } else if (screen.partial.isNotBlank()) {
                text(screen.partial, style = TextStyle.BODY, color = TextColor.SECONDARY)
            } else {
                text("…", style = TextStyle.BODY, color = TextColor.SECONDARY)
            }
            button(
                label = "Back",
                style = ButtonStyle.SECONDARY,
                iconName = IconName.ARROW_LEFT,
                onClick = { publish(GlassesEvent.Back) },
            )
        }
    }

    private suspend fun renderProfileCard(
        display: Display,
        screen: GlassesScreen.ProfileCard,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(gap = 8, padding = 20, background = FlexBoxBackground.CARD) {
            val avatar = screen.avatarUri
            if (!avatar.isNullOrBlank() && avatar.startsWith("https://")) {
                // DAT image only accepts HTTPS URIs — local content://
                // avatars are skipped here. The phone-side person UI
                // hosts those.
                image(
                    uri = avatar,
                    sizePreset = ImageSize.ICON,
                    cornerRadius = CornerRadius.MEDIUM,
                )
            } else {
                icon(name = IconName.PERSON, style = IconStyle.FILLED)
            }
            text(screen.displayName, style = TextStyle.HEADING)
            if (!screen.toneLabel.isNullOrBlank()) {
                text(screen.toneLabel, style = TextStyle.BODY, color = TextColor.SECONDARY)
            }
            // Cap to 3 traits / facts so the card fits.
            screen.keyPoints.take(3).forEach { point ->
                text("• $point", style = TextStyle.BODY)
            }
            if (screen.lastInteractionMs != null) {
                text(
                    "Last seen " + relativeTime(screen.lastInteractionMs),
                    style = TextStyle.BODY,
                    color = TextColor.SECONDARY,
                )
            }
            button(
                label = "Back",
                style = ButtonStyle.SECONDARY,
                iconName = IconName.ARROW_LEFT,
                onClick = { publish(GlassesEvent.Back) },
            )
        }
    }

    private suspend fun renderInsightTicker(
        display: Display,
        screen: GlassesScreen.InsightTicker,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(
            gap = 12,
            padding = 20,
            background = FlexBoxBackground.CARD,
            onClick = { publish(GlassesEvent.Back) },
        ) {
            icon(name = IconName.STAR_CIRCLE_TRIANGLE_AI, style = IconStyle.FILLED)
            text(screen.text, style = TextStyle.BODY)
        }
    }

    private suspend fun renderFavoritesList(
        display: Display,
        screen: GlassesScreen.FavoritesList,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(gap = 6, padding = 16, background = FlexBoxBackground.CARD) {
            text("Favorites", style = TextStyle.HEADING)
            if (screen.items.isEmpty()) {
                text("No favorites yet", style = TextStyle.BODY, color = TextColor.SECONDARY)
            } else {
                // Cap to 6 so the list fits on one display frame.
                screen.items.take(6).forEach { fav ->
                    button(
                        label = fav.displayName,
                        style = ButtonStyle.SECONDARY,
                        iconName = IconName.PERSON,
                        onClick = { publish(GlassesEvent.OpenContact(fav.nameKey)) },
                    )
                }
            }
            button(
                label = "Back",
                style = ButtonStyle.SECONDARY,
                iconName = IconName.ARROW_LEFT,
                onClick = { publish(GlassesEvent.Back) },
            )
        }
    }

    private suspend fun renderPhotoMemoryToast(
        display: Display,
        screen: GlassesScreen.PhotoMemoryToast,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(
            gap = 8,
            padding = 20,
            background = FlexBoxBackground.CARD,
            onClick = { publish(GlassesEvent.Back) },
        ) {
            icon(name = IconName.VIDEO_CAMERA, style = IconStyle.FILLED)
            text("Saved to timeline", style = TextStyle.HEADING)
            val caption = screen.caption
            if (!caption.isNullOrBlank()) {
                text(caption, style = TextStyle.BODY, color = TextColor.SECONDARY)
            }
        }
    }

    private suspend fun renderError(
        display: Display,
        screen: GlassesScreen.Error,
        publish: (GlassesEvent) -> Unit,
    ) = display.sendContent {
        flexBox(gap = 8, padding = 20, background = FlexBoxBackground.CARD) {
            icon(name = IconName.EXCLAMATION_TRIANGLE, style = IconStyle.FILLED)
            text("Mythara", style = TextStyle.HEADING)
            text(screen.message, style = TextStyle.BODY, color = TextColor.SECONDARY)
            button(
                label = "OK",
                style = ButtonStyle.PRIMARY,
                iconName = IconName.CHECKMARK,
                onClick = { publish(GlassesEvent.Back) },
            )
        }
    }

    private fun relativeTime(ms: Long): String {
        val deltaMs = System.currentTimeMillis() - ms
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            minutes < 10_080 -> "${minutes / 1440}d ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
        }
    }
}
