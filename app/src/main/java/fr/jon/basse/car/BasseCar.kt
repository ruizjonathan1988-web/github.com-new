package fr.jon.basse.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import fr.jon.basse.EqService
import fr.jon.basse.EqState
import fr.jon.basse.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Basse sur l'écran Android Auto (catégorie « objets connectés » : on pilote l'égaliseur, on ne lit pas de musique). */
class BasseCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = BasseSession()
}

class BasseSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        EqState.init(carContext)
        return MainScreen(carContext)
    }
}

private fun icon(ctx: CarContext, res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(ctx, res)).apply { if (tint != null) setTint(tint) }.build()

private fun limit(ctx: CarContext): Int = runCatching {
    ctx.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
}.getOrDefault(6)

/** Écran principal : ON/OFF, Punch, puis les réglages voiture du CX-5. */
class MainScreen(ctx: CarContext) : Screen(ctx) {
    private var job: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                job = lifecycleScope.launch { EqState.settings.drop(1).collect { invalidate() } }
            }
            override fun onStop(owner: LifecycleOwner) { job?.cancel() }
        })
    }

    override fun onGetTemplate(): Template {
        val s = EqState.settings.value
        val cur = EqState.find(s.presetId)?.second?.name ?: "—"
        val items = ItemList.Builder()
        items.addItem(
            GridItem.Builder().setTitle(if (s.enabled) "Basse ON" else "Basse OFF").setText(if (s.enabled) "Toucher pour couper" else "Toucher pour allumer")
                .setImage(icon(carContext, R.drawable.ic_power, if (s.enabled) CarColor.GREEN else CarColor.DEFAULT))
                .setOnClickListener {
                    val on = !EqState.settings.value.enabled
                    EqState.update(carContext) { it.copy(enabled = on) }
                    if (on) EqService.start(carContext)
                }.build(),
        )
        val punchNames = listOf("Coupé", "Léger", "Moyen", "Fort")
        items.addItem(
            GridItem.Builder().setTitle("Punch").setText(punchNames[s.punch.coerceIn(0, 3)])
                .setImage(icon(carContext, R.drawable.ic_punch, if (s.punch > 0) CarColor.RED else CarColor.DEFAULT))
                .setOnClickListener { EqState.update(carContext) { it.copy(punch = (it.punch + 1) % 3) } } // Fort exclu en voiture
                .build(),
        )
        val styles = EqState.catalog.families.firstOrNull { it.id == "cx5" }?.let { f -> f.styles.flatMap { listOf(it) + it.subs } }.orEmpty()
        val max = limit(carContext)
        styles.take((max - 2).coerceAtLeast(2)).forEach { p ->
            val sel = p.id == s.presetId
            items.addItem(
                GridItem.Builder().setTitle(p.name).setText(if (sel) "● actif" else " ")
                    .setImage(icon(carContext, R.drawable.ic_notif, if (sel) CarColor.RED else CarColor.DEFAULT))
                    .setOnClickListener {
                        EqState.select(carContext, p.id)
                        if (EqState.settings.value.enabled) EqService.start(carContext)
                    }.build(),
            )
        }
        val strip = ActionStrip.Builder()
        if (s.favorites.isNotEmpty()) strip.addAction(Action.Builder().setTitle("Favoris").setOnClickListener {
            screenManager.push(ListScreen(carContext, favorites = true))
        }.build())
        strip.addAction(Action.Builder().setTitle("Tous").setOnClickListener { screenManager.push(ListScreen(carContext, favorites = false)) }.build())
        return GridTemplate.Builder()
            .setTitle("Basse · $cur")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .setActionStrip(strip.build())
            .build()
    }
}

/** Favoris, ou tous les réglages voiture, sous forme de grille paginée par l'hôte. */
class ListScreen(ctx: CarContext, private val favorites: Boolean) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val s = EqState.settings.value
        val ids = if (favorites) s.favorites else
            EqState.catalog.families.firstOrNull { it.id == "cx5" }?.styles?.flatMap { listOf(it.id) + it.subs.map { x -> x.id } }.orEmpty() +
                listOf("universel.tout_terrain", "universel.tout_terrain_basses", "video.nuit")
        val items = ItemList.Builder()
        ids.distinct().take(limit(carContext)).forEach { id ->
            val p = EqState.find(id)?.second ?: return@forEach
            val sel = id == s.presetId
            items.addItem(
                GridItem.Builder().setTitle(p.name).setText(if (sel) "● actif" else " ")
                    .setImage(icon(carContext, R.drawable.ic_notif, if (sel) CarColor.RED else CarColor.DEFAULT))
                    .setOnClickListener {
                        EqState.select(carContext, id)
                        if (EqState.settings.value.enabled) EqService.start(carContext)
                        screenManager.pop()
                    }.build(),
            )
        }
        return GridTemplate.Builder().setTitle(if (favorites) "Favoris" else "Réglages voiture")
            .setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}
