package com.zilv.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zilv.app.domain.permission.PermissionStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "zilv_settings")

/**
 * 本地设置。
 *
 * 目前主要保存「用户手动确认过的权限步骤」—— 因为受限设置与自启动管理
 * 在 Android 上没有查询 API（见 [com.zilv.app.permission.PermissionChecker]）。
 */
class SettingsRepository(private val context: Context) {

    private val manualStepsKey = stringSetPreferencesKey("manual_confirmed_permission_steps")

    val manualConfirmedSteps: Flow<Set<PermissionStep>> =
        context.settingsDataStore.data.map { prefs ->
            (prefs[manualStepsKey] ?: emptySet())
                .mapNotNull { runCatching { PermissionStep.valueOf(it) }.getOrNull() }
                .toSet()
        }

    suspend fun manualConfirmedStepsOnce(): Set<PermissionStep> = manualConfirmedSteps.first()

    suspend fun confirmStep(step: PermissionStep) {
        context.settingsDataStore.edit { prefs ->
            prefs[manualStepsKey] = (prefs[manualStepsKey] ?: emptySet()) + step.name
        }
    }

    suspend fun revokeStep(step: PermissionStep) {
        context.settingsDataStore.edit { prefs ->
            prefs[manualStepsKey] = (prefs[manualStepsKey] ?: emptySet()) - step.name
        }
    }
}
