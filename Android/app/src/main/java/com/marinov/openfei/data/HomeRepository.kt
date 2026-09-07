package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import androidx.core.content.edit

data class CarouselItem(val imageUrl: String?, val linkUrl: String?)

object HomeRepository {
    private const val PREFS_NAME = "HomeFragmentCache"
    private const val KEY_CAROUSEL_ITEMS = "carousel_items"
    private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
    private const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    suspend fun obterCarrossel(context: Context, online: Boolean): List<CarouselItem> {
        if (!online) {
            return getCarouselCache(context)
        }

        return try {
            val doc = fetchPageData(HOME_URL)
            val newCarousel = processPageContent(doc)
            if (newCarousel.isNotEmpty()) {
                saveCarouselCache(newCarousel, context)
                newCarousel
            } else {
                // Fallback: se a busca online retornar vazio, tenta usar o cache para não deixar o carrossel sumir
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

    @Throws(IOException::class)
    private suspend fun fetchPageData(url: String): Document? = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies.isNullOrBlank()) return@withContext null
        Jsoup.connect(url)
            .header("Cookie", cookies)
            .userAgent(USER_AGENT)
            .timeout(20000)
            .get()
    }

    private fun processPageContent(doc: Document?): List<CarouselItem> {
        if (doc == null) return emptyList()
        val newCarousel = mutableListOf<CarouselItem>()
        for (item in doc.select("#carousel-example-generic .item")) {
            val linkHref = item.selectFirst("a")?.attr("href") ?: continue
            val imgSrc = item.selectFirst("img")?.attr("src") ?: continue
            val absoluteImageUrl = if (imgSrc.startsWith("http")) imgSrc else "https://interage.fei.org.br$imgSrc"
            newCarousel.add(CarouselItem(absoluteImageUrl, linkHref))
        }
        return newCarousel
    }
}