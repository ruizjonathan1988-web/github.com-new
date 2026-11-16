package fr.jon.basse

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tuile des réglages rapides : appui = ON/OFF. Si Basse est allumée et qu'il y a des favoris, passe au suivant. */
class BasseTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        EqState.init(this)
        val s = EqState.settings.value
        when {
            !s.enabled -> { EqState.update(this) { it.copy(enabled = true) }; EqService.start(this) }
            s.favorites.size > 1 && s.favorites.indexOf(s.presetId) < s.favorites.size - 1 -> EqState.nextFavorite(this)
            else -> EqState.update(this) { it.copy(enabled = false) }
        }
        refresh()
    }

    private fun refresh() {
        EqState.init(this)
        val s = EqState.settings.value
        val tile = qsTile ?: return
        tile.state = if (s.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Basse"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (s.enabled) EqState.catalog.find(s.presetId)?.second?.name else "Coupé"
        tile.updateTile()
    }
}
