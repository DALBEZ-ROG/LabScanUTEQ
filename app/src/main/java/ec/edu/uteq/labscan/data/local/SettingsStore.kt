package ec.edu.uteq.labscan.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Preferencias del estudiante, en DataStore Preferences.
 *
 * Son cuatro ajustes y ninguna consulta: no hace falta Room (CLAUDE.md). DataStore da lecturas
 * como `Flow` y escrituras suspendidas, que es exactamente lo que necesita una pantalla de
 * ajustes observada desde Compose.
 *
 * El delegado `preferencesDataStore` es de nivel de fichero a proposito: abrir dos DataStore
 * sobre el mismo archivo lanza en tiempo de ejecucion, y declararlo aqui garantiza que solo
 * exista uno en todo el proceso aunque alguien construya dos [SettingsStore].
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "labscan_ajustes")

class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    /**
     * Fuente comun de todas las lecturas.
     *
     * Un archivo de preferencias corrupto no puede tumbar la app: se empieza de cero con los
     * valores de fabrica. Se centraliza aqui para que ninguna preferencia futura se olvide de
     * hacerlo.
     */
    private val preferences: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) {
            Log.w(App.LOG_TAG, "No se pudieron leer los ajustes; se usan los de fabrica", error)
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    /**
     * Si las respuestas del asistente se leen solas al llegar.
     *
     * **Activada por defecto.** El usuario objetivo entra al laboratorio con el telefono en
     * una mano y guantes puestos: si tuviera que pulsar el altavoz cada vez, la voz dejaria
     * de ser la via principal de uso y volveria a ser un adorno.
     */
    val autoReadAnswers: Flow<Boolean> = preferences.map { it[AUTO_READ] ?: DEFAULT_AUTO_READ }

    suspend fun setAutoReadAnswers(enabled: Boolean) {
        store.edit { it[AUTO_READ] = enabled }
    }

    /**
     * Umbral de confianza del detector.
     *
     * El valor de fabrica no se fija aqui sino que llega de `model_config.json`, porque quien
     * entrena el modelo sabe mejor que nadie donde esta su punto de corte razonable. Este
     * ajuste solo permite moverlo dentro de [MIN_THRESHOLD]..[MAX_THRESHOLD], que es el rango
     * util: por debajo de 0,25 la pantalla se llena de cajas falsas y por encima de 0,75 casi
     * nada llega a dibujarse.
     *
     * @param fallback lo que diga `model_config.json` mientras el estudiante no lo toque.
     */
    fun confidenceThreshold(fallback: Float): Flow<Float> = preferences.map { stored ->
        (stored[CONFIDENCE] ?: fallback).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    suspend fun setConfidenceThreshold(value: Float) {
        store.edit { it[CONFIDENCE] = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) }
    }

    /**
     * Camara con la que arranca el escaner.
     *
     * Se guarda el nombre y no el ordinal del enum: reordenar `CameraFacing` algun dia no debe
     * cambiar en silencio la preferencia de nadie.
     */
    val defaultCameraBack: Flow<Boolean> =
        preferences.map { (it[DEFAULT_CAMERA] ?: CAMERA_BACK) == CAMERA_BACK }

    suspend fun setDefaultCameraBack(back: Boolean) {
        store.edit { it[DEFAULT_CAMERA] = if (back) CAMERA_BACK else CAMERA_FRONT }
    }

    /**
     * Si la ficha se abre sola cuando una deteccion supera [AUTO_OPEN_CONFIDENCE].
     *
     * **Activada por defecto.** El estudiante entra al laboratorio con el telefono en una
     * mano; obligarle a acertar en un cuadro que se mueve para ver la ficha es justo la
     * friccion que hace que la app no se use.
     *
     * Se puede apagar porque en una mesa con varios equipos parecidos la ficha puede abrirse
     * sola una y otra vez, y en medio de una demostracion eso es peor que un toque de mas.
     */
    val autoOpenSheet: Flow<Boolean> = preferences.map { it[AUTO_OPEN] ?: DEFAULT_AUTO_OPEN }

    suspend fun setAutoOpenSheet(enabled: Boolean) {
        store.edit { it[AUTO_OPEN] = enabled }
    }

    /**
     * URL del backend RAG, vacia si se usa la de la variante de compilacion.
     *
     * Es editable porque durante las pruebas el backend cambia de direccion cada vez que se
     * conecta a otra red del campus, y recompilar la app para cambiar una IP es absurdo.
     * Quien la aplica es [ec.edu.uteq.labscan.data.remote.BaseUrlInterceptor].
     */
    val backendUrl: Flow<String> = preferences.map { it[BACKEND_URL].orEmpty() }

    suspend fun setBackendUrl(url: String) {
        store.edit { it[BACKEND_URL] = url.trim() }
    }

    companion object {
        /** Limites del control deslizante de confianza, fijados por la actividad. */
        const val MIN_THRESHOLD = 0.25f
        const val MAX_THRESHOLD = 0.75f

        private val AUTO_READ = booleanPreferencesKey("auto_read_answers")
        private val CONFIDENCE = floatPreferencesKey("confidence_threshold")
        private val DEFAULT_CAMERA = stringPreferencesKey("default_camera")
        private val BACKEND_URL = stringPreferencesKey("backend_url")
        private val AUTO_OPEN = booleanPreferencesKey("auto_open_sheet")

        /**
         * Confianza a partir de la cual la ficha se abre sola.
         *
         * 0,70 y no el umbral de dibujo (0,45): una cosa es dibujar un cuadro, que si se
         * equivoca solo estorba, y otra abrir una ficha a pantalla completa, que si se
         * equivoca interrumpe. El listón para actuar solo tiene que ser más alto que el
         * listón para sugerir.
         */
        const val AUTO_OPEN_CONFIDENCE = 0.70f

        private const val DEFAULT_AUTO_READ = true
        private const val DEFAULT_AUTO_OPEN = true
        private const val CAMERA_BACK = "BACK"
        private const val CAMERA_FRONT = "FRONT"
    }
}
