package eu.stgm.sol

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Senza SolApp: niente WorkManager né notifiche nelle prove con le schermate vere. */
class AppDiProva : Application()

/**
 * Robolectric non disegna le finestre dei dialoghi e dei fogli, e Compose ne
 * aspetterebbe per sempre la misura: a ogni giro d'attesa si misurano a mano tutte
 * le radici di Compose (come nel fotografo di Pactum). Sul telefono non serve.
 */
class RegolaFinestre : TestWatcher() {
    private val misuratore = object : IdlingResource {
        override fun getName() = "misura-dialoghi-e-fogli"
        override fun isIdleNow(): Boolean {
            try {
                misuraTutto()
            } catch (_: Throwable) {
            }
            return true
        }
        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {}
    }

    override fun starting(description: Description) {
        IdlingRegistry.getInstance().register(misuratore)
    }

    override fun finished(description: Description) {
        IdlingRegistry.getInstance().unregister(misuratore)
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun misuraTutto() {
        val radici = try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val istanza = wmg.getMethod("getInstance").invoke(null)
            val campo = wmg.getDeclaredField("mRoots").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (campo.get(istanza) as List<Any>).mapNotNull { it.javaClass.getMethod("getView").invoke(it) as? View }
        } catch (e: ReflectiveOperationException) {
            emptyList()
        }
        fun cerca(v: View) {
            if (v is ViewRootForTest && v.view.isAttachedToWindow) v.measureAndLayoutForTest()
            if (v is ViewGroup) for (i in 0 until v.childCount) cerca(v.getChildAt(i))
        }
        radici.toList().forEach { cerca(it) }
    }
}
