package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.presentation.navigation.AppNavigator
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.theme.cpSheetInnerSurface
import cp.player.core.music.ArtistSummary

/**
 * Resolve clickable artist entries from structured [artists], falling back to a single
 * entry if only [fallbackName] + [fallbackResourceId] are known.
 */
internal fun resolveClickableArtists(
    artists: List<ArtistSummary>,
    fallbackName: String? = null,
    fallbackId: Long? = null,
    fallbackResourceId: String? = null,
): List<ArtistSummary> {
    val valid = artists.filter { it.name.isNotBlank() && it.resourceId.isNotBlank() }
    if (valid.isNotEmpty()) return valid
    val name = fallbackName?.takeIf { it.isNotBlank() } ?: return emptyList()
    val resId = fallbackResourceId?.takeIf { it.isNotBlank() }
        ?: fallbackId?.takeIf { it > 0L }?.toString()
        ?: return emptyList()
    return listOf(
        ArtistSummary(
            id = fallbackId ?: resId.toLongOrNull() ?: 0L,
            name = name,
            avatarUrl = null,
            resourceId = resId,
        )
    )
}

internal fun navigateToArtistProfile(navigator: AppNavigator, artist: ArtistSummary) {
    navigator.push(AppRoute.UserProfile(artist.id, artist.name, artist.resourceId))
}

/**
 * Material 3 Expressive bottom sheet listing multiple artists so the user can choose
 * which artist profile to open.
 */
@Composable
fun ArtistPickerSheet(
    artists: List<ArtistSummary>,
    onSelect: (ArtistSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = cpStrings()
    val selectable = artists.filter { it.name.isNotBlank() && it.resourceId.isNotBlank() }
    if (selectable.isEmpty()) return
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            CpText(
                text = s.playlistSheet.artistFallback,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            selectable.forEachIndexed { index, artist ->
                var resolvedAvatar by remember(artist.resourceId, artist.avatarUrl) {
                    mutableStateOf(artist.avatarUrl)
                }
                LaunchedEffect(artist.resourceId) {
                    if (resolvedAvatar.isNullOrBlank()) {
                        val profile = runCatching { AppModel.musicRepository.getArtistProfile(artist.resourceId) }.getOrNull()
                        val avatar = profile?.avatarUrl
                        if (!avatar.isNullOrBlank()) {
                            resolvedAvatar = avatar
                        }
                    }
                }
                LegacyListItem(
                    index = index,
                    total = selectable.size,
                    onClick = {
                        onDismiss()
                        onSelect(artist)
                    },
                    containerColor = cpSheetInnerSurface(),
                    leadingContent = { ArtistAvatar(resolvedAvatar, 44.dp) },
                    headlineContent = {
                        CpText(
                            artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(CpIconSize.list),
                        )
                    },
                )
            }
        }
    }
}
