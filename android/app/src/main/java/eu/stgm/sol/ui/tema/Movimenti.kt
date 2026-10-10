package eu.stgm.sol.ui.tema

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** docs/aspetto.md §5: brevi e calmi, tra 150 e 250 ms. */
const val DURATA_MOVIMENTO = 200

/** Una curva morbida, senza rimbalzi; istantanea se il sistema ha tolto le animazioni. */
@Composable
fun <T> morbido(durataMs: Int = DURATA_MOVIMENTO): FiniteAnimationSpec<T> =
    if (LocalMovimenti.current) tween(durataMs, easing = FastOutSlowInEasing) else snap()

/**
 * Le righe di un elenco che entrano, escono e si spostano con un movimento breve
 * (mail nuove in cima, mail spostate in un altro gruppo). Senza animazioni: niente.
 */
@Composable
fun LazyItemScope.movimentoRiga(): Modifier =
    if (LocalMovimenti.current) {
        Modifier.animateItem(
            fadeInSpec = tween(DURATA_MOVIMENTO, easing = FastOutSlowInEasing),
            placementSpec = tween(DURATA_MOVIMENTO, easing = FastOutSlowInEasing),
            fadeOutSpec = tween(DURATA_MOVIMENTO, easing = FastOutSlowInEasing),
        )
    } else {
        Modifier
    }
