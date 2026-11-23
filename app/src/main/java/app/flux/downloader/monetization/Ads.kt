package app.flux.downloader.monetization

import android.app.Activity
import app.flux.downloader.engine.Choice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Configuración de anuncios. Los IDs de abajo son los de PRUEBA que publica Google; al tener la cuenta de AdMob
 * se reemplazan por los reales (ver ADMOB_Y_PREMIUM.md) y [STUB_MODE] pasa a false.
 */
object AdConfig {
    /** true = anuncios simulados y espacios reservados (todavía no hay SDK ni cuenta de AdMob). */
    const val STUB_MODE = true

    const val ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"

    const val REWARDED_DOWNLOAD = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_VAULT_UNLOCK = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_VAULT_SLOT = "ca-app-pub-3940256099942544/5224354917"

    const val BANNER_HOME = "ca-app-pub-3940256099942544/6300978111"
    const val BANNER_PROGRESS = "ca-app-pub-3940256099942544/6300978111"
}

/** Dónde se muestra un anuncio con recompensa. Cada uno tiene su propio ID de bloque en AdMob. */
enum class AdPlacement(val adUnitId: String) {
    DOWNLOAD(AdConfig.REWARDED_DOWNLOAD),
    VAULT_UNLOCK(AdConfig.REWARDED_VAULT_UNLOCK),
    VAULT_SLOT(AdConfig.REWARDED_VAULT_SLOT),
}

/** Conexión con el SDK real de AdMob (se implementa cuando haya cuenta; ver ADMOB_Y_PREMIUM.md). */
interface RewardedAdProvider {
    /** Muestra el anuncio y devuelve true solo si el usuario lo vio completo y ganó la recompensa. */
    suspend fun show(placement: AdPlacement, activity: Activity?): Boolean
}

/** Un anuncio simulado pendiente de mostrarse (lo dibuja [RewardedAdOverlay]). */
class AdRequest(val placement: AdPlacement, val purpose: String) {
    val result = CompletableDeferred<Boolean>()
}

object Ads {
    /** null = modo de prueba (anuncio simulado). Con AdMob real se asigna un [RewardedAdProvider]. */
    var provider: RewardedAdProvider? = null

    private var activityRef: WeakReference<Activity>? = null
    val activity: Activity? get() = activityRef?.get()
    fun attach(a: Activity) { activityRef = WeakReference(a) }
    fun detach(a: Activity) { if (activityRef?.get() === a) activityRef = null }

    // Un anuncio a la vez: si se piden varios (p. ej. "descargar todos"), se muestran uno tras otro
    private val gate = Mutex()
    private val _pending = MutableStateFlow<AdRequest?>(null)
    val pending: StateFlow<AdRequest?> = _pending.asStateFlow()

    /** true = el usuario ganó la recompensa; false = cerró el anuncio antes de tiempo. */
    suspend fun showRewarded(placement: AdPlacement, purpose: String): Boolean = gate.withLock {
        val real = provider
        if (real != null) return@withLock real.show(placement, activity)
        val req = AdRequest(placement, purpose)
        _pending.value = req
        try {
            req.result.await()
        } finally {
            _pending.value = null
        }
    }

    fun finish(req: AdRequest, rewarded: Boolean) {
        req.result.complete(rewarded)
    }
}

/**
 * Regla de las descargas: cada video se descarga gratis viendo un anuncio con recompensa, salvo la calidad 240p
 * (gratis y sin anuncios). Premium descarga todo sin anuncios.
 */
object DownloadGate {
    const val FREE_MAX_HEIGHT = 240

    fun isFreeQuality(height: Int?, audioOnly: Boolean): Boolean =
        !audioOnly && height != null && height in 1..FREE_MAX_HEIGHT

    fun requiresAd(height: Int?, audioOnly: Boolean): Boolean =
        !PremiumState.isPremium.value && !isFreeQuality(height, audioOnly)

    /** true = se puede empezar la descarga ahora. */
    suspend fun authorize(title: String, choice: Choice): Boolean {
        if (!requiresAd(choice.height, choice.audioOnly)) return true
        return Ads.showRewarded(AdPlacement.DOWNLOAD, "descargar “" + title.take(60) + "”")
    }

    /** Mensaje corto que se muestra junto a cada calidad. */
    fun tag(height: Int?, audioOnly: Boolean = false, premium: Boolean = PremiumState.isPremium.value): String = when {
        premium -> "Sin anuncios · Premium"
        isFreeQuality(height, audioOnly) -> "Gratis · sin anuncios"
        else -> "Gratis viendo un anuncio"
    }
}
