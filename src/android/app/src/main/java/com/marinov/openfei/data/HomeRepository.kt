package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class CarouselItem(val imageUrl: String?, val linkUrl: String?)

object HomeRepository {
    private const val PREFS_NAME = "HomeFragmentCache"
    private const val KEY_CAROUSEL_ITEMS = "carousel_items"
    private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"

    suspend fun obterCarrossel(context: Context, online: Boolean): List<CarouselItem> {
        if (!online) {
            return getCarouselCache(context)
        }
        return try {
            val newCarousel = fetchCarouselFromRust()
            if (newCarousel.isNotEmpty()) {
                saveCarouselCache(newCarousel, context)
                newCarousel
            } else {
                getCarouselCache(context)
            }
        } catch (e: Exception) {
            Log.e("HomeRepository", "Erro ao buscar carrossel online, retornando cache", e)
            getCarouselCache(context)
        }
    }

    private fun getCarouselCache(context: Context): List<CarouselItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CAROUSEL_ITEMS, null) ?: return emptyList()
        val type = object : TypeToken<MutableList<CarouselItem>>() {}.type
        return try {
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("HomeRepository", "Erro ao ler cache do carrossel", e)
            emptyList()
        }
    }

    private fun saveCarouselCache(items: List<CarouselItem>, context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_CAROUSEL_ITEMS, Gson().toJson(items))
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private suspend fun fetchCarouselFromRust(): List<CarouselItem> = withContext(Dispatchers.IO) {
        // ★ CORREÇÃO: Garante sessão FEI antes de buscar carrossel
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para carrossel")
        }

        when (val result = OpenFeiCore.fetchCarousel()) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    CarouselItem(
                        imageUrl = core.imageUrl,
                        linkUrl = core.linkUrl
                    )
                }
            }
            is CoreResult.Error -> {
                if (result.code == "SESSION_EXPIRED") {
                    throw SessionExpiredException(result.message)
                }
                throw IOException(result.message)
            }
        }
    }
}