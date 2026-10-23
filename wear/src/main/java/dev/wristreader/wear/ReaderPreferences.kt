package dev.wristreader.wear

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore("reader")
data class ReaderSettings(
    val mode: String = "slides", val vertical: Boolean = false,
    val font: Int = 16, val line: Float = 1.4f, val margin: Int = 0,
    val dark: Boolean = true, val sensitivity: Float = 1f,
    val remember: Boolean = true, val fitWidth: Boolean = false, val textPaged: Boolean = false,
    val progressRing: Boolean = true
)
data class ReadingPosition(val index: Int = 0, val offset: Int = 0)
class ReaderPreferences(context: Context) {
    private val store = context.applicationContext.readerDataStore
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = store.data.map { p ->
        ReaderSettings(p[stringPreferencesKey("mode")] ?: "slides", p[booleanPreferencesKey("vertical")] ?: false,
            p[intPreferencesKey("font")] ?: 16, p[floatPreferencesKey("line")] ?: 1.4f,
            p[intPreferencesKey("margin")] ?: 0, p[booleanPreferencesKey("dark")] ?: true,
            p[floatPreferencesKey("sensitivity")] ?: 1f, p[booleanPreferencesKey("remember")] ?: true,
            p[booleanPreferencesKey("fitWidth")] ?: false, p[booleanPreferencesKey("textPaged")] ?: false,
            p[booleanPreferencesKey("progressRing")] ?: true)
    }
    suspend fun save(s: ReaderSettings) { store.edit { p ->
        p[stringPreferencesKey("mode")] = s.mode; p[booleanPreferencesKey("vertical")] = s.vertical
        p[intPreferencesKey("font")] = s.font; p[floatPreferencesKey("line")] = s.line
        p[intPreferencesKey("margin")] = s.margin; p[booleanPreferencesKey("dark")] = s.dark
        p[floatPreferencesKey("sensitivity")] = s.sensitivity; p[booleanPreferencesKey("remember")] = s.remember
        p[booleanPreferencesKey("fitWidth")] = s.fitWidth; p[booleanPreferencesKey("textPaged")] = s.textPaged
        p[booleanPreferencesKey("progressRing")] = s.progressRing
    } }
    fun position(id: String, mode: String) = store.data.map {
        ReadingPosition(it[intPreferencesKey("${id}_${mode}_index")] ?: 0,
            it[intPreferencesKey("${id}_${mode}_offset")] ?: 0)
    }
    suspend fun savePosition(id: String, mode: String, position: ReadingPosition) {
        store.edit { p -> p[intPreferencesKey("${id}_${mode}_index")] = position.index
            p[intPreferencesKey("${id}_${mode}_offset")] = position.offset }
    }
}
