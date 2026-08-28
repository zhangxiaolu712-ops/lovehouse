package fyi.b612.lovehouse.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.loveHouseDataStore by preferencesDataStore(name = "lovehouse_native")

interface LocalStorage {
    fun observeString(key: String): Flow<String?>
    suspend fun readString(key: String): String?
    suspend fun writeString(key: String, value: String)
    suspend fun remove(key: String)
}

class DataStoreLocalStorage(
    private val context: Context,
) : LocalStorage {
    override fun observeString(key: String): Flow<String?> {
        val preferenceKey = stringPreferencesKey(key)
        return context.loveHouseDataStore.data.map { preferences -> preferences[preferenceKey] }
    }

    override suspend fun readString(key: String): String? = observeString(key).first()

    override suspend fun writeString(key: String, value: String) {
        val preferenceKey = stringPreferencesKey(key)
        context.loveHouseDataStore.edit { preferences -> preferences[preferenceKey] = value }
    }

    override suspend fun remove(key: String) {
        val preferenceKey = stringPreferencesKey(key)
        context.loveHouseDataStore.edit { preferences -> preferences.remove(preferenceKey) }
    }
}
