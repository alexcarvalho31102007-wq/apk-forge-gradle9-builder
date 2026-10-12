package com.example.util

import android.content.Context
import android.content.SharedPreferences

class SecureStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_LAST_REPO = "last_repo"
        private const val KEY_HISTORY = "build_history_json"
    }

    fun saveGitHubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
    }

    fun getGitHubToken(): String {
        return prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
    }

    fun clearGitHubToken() {
        prefs.edit().remove(KEY_GITHUB_TOKEN).apply()
    }

    fun saveLastRepo(repo: String) {
        prefs.edit().putString(KEY_LAST_REPO, repo).apply()
    }

    fun getLastRepo(): String {
        return prefs.getString(KEY_LAST_REPO, "") ?: ""
    }

    fun saveHistoryJson(json: String) {
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    fun getHistoryJson(): String {
        return prefs.getString(KEY_HISTORY, "") ?: ""
    }
}
