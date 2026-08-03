package eu.stgm.sol.dati

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.store by preferencesDataStore(name = "impostazioni")

// Un solo interruttore per ora: l'orecchio. Lo leggono il servizio e il tile;
// deve sopravvivere a riavvii del servizio, per questo non è una variabile.
object Impostazioni {

    private val ORECCHIO = booleanPreferencesKey("orecchio_attivo")

    suspend fun orecchioAttivo(ctx: Context): Boolean =
        ctx.store.data.first()[ORECCHIO] ?: true

    suspend fun impostaOrecchio(ctx: Context, attivo: Boolean) {
        ctx.store.edit { it[ORECCHIO] = attivo }
    }
}
