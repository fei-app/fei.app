package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class ProvasFragment : Fragment() {

    private companion object {
        const val TAG = "ProvasFragment"
        const val PROJECT_PATH = "etapa.app%2Fschooltests"
        const val BRANCH = "main"
        const val API_BASE = "https://gitlab.com/api/v4/projects/$PROJECT_PATH/repository"
        const val CHANNEL_ID = "provas_download_channel"
        val notifIdCounter = AtomicInteger(1000)
    }

    private lateinit var searchView: SearchView
    private lateinit var recyclerProvas: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var adapter: ProvasAdapter
    private val allItems = mutableListOf<RepoItem>()
    private var currentPath = ""
    private var fetchJob: Job? = null
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton

    private var pendingDownload: Pair<String, String>? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingDownload?.let { (url, name) ->
                    lifecycleScope.launch { downloadFile(url, name) }
                }
            } else {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Permissão negada. Não é possível salvar o arquivo.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            pendingDownload = null
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_provas, container, false)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        searchView = view.findViewById(R.id.search_view)
        recyclerProvas = view.findViewById(R.id.recyclerProvas)
        progressBar = view.findViewById(R.id.progress_circular)

        createNotificationChannel()

        searchView.queryHint = "Buscar provas..."
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText)
                return true
            }
        })

        recyclerProvas.layoutManager = LinearLayoutManager(requireContext())
        adapter = ProvasAdapter(emptyList()) { item -> onItemClick(item) }
        recyclerProvas.adapter = adapter

        if (hasInternetConnection()) {
            startFetch()
        } else {
            showNoInternetUI()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                (activity as? MainActivity)?.showBottomNavigation()
                if (currentPath.isNotEmpty()) {
                    currentPath = getParentPath(currentPath)
                    startFetch()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
    }

    // ─── Internet / UI ────────────────────────────────────────────────────────

    private fun hasInternetConnection(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showNoInternetUI() {
        recyclerProvas.visibility = View.GONE
        searchView.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
        btnTentarNovamente.setOnClickListener {
            if (hasInternetConnection()) {
                layoutSemInternet.visibility = View.GONE
                startFetch()
            }
        }
    }

    private fun getParentPath(path: String): String {
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash == -1) "" else path.substring(0, lastSlash)
    }

    // ─── Listagem GitLab ──────────────────────────────────────────────────────

    private fun startFetch() {
        recyclerProvas.visibility = View.VISIBLE
        searchView.visibility = View.VISIBLE
        fetchJob?.cancel()
        fetchJob = lifecycleScope.launch { fetchFiles(currentPath) }
    }

    private suspend fun fetchFiles(path: String) {
        withContext(Dispatchers.Main) { progressBar.visibility = View.VISIBLE }

        val results = withContext(Dispatchers.IO) { fetchFilesFromGitLab(path) }

        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            allItems.clear()
            allItems.addAll(results)
            adapter.updateData(results)
        }
    }

    private fun fetchFilesFromGitLab(path: String): List<RepoItem> {
        val list = mutableListOf<RepoItem>()
        var page = 1

        while (true) {
            var conn: HttpURLConnection? = null
            try {
                val params = StringBuilder("?ref=$BRANCH&per_page=100&page=$page")
                if (path.isNotEmpty()) params.append("&path=${URLEncoder.encode(path, "UTF-8")}")

                val url = URL("$API_BASE/tree$params")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("PRIVATE-TOKEN", BuildConfig.GITLAB_PAT)

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Erro HTTP ${conn.responseCode} na página $page")
                    break
                }

                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line)
                }

                val arr = JSONArray(sb.toString())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val gitlabType = o.getString("type") // "tree" = pasta, "blob" = arquivo
                    val itemPath = o.getString("path")
                    val normalizedType = if (gitlabType == "tree") "dir" else "file"
                    val downloadUrl = if (gitlabType == "blob") {
                        val encodedPath = URLEncoder.encode(itemPath, "UTF-8").replace("+", "%20")
                        "$API_BASE/files/$encodedPath/raw?ref=$BRANCH"
                    } else ""

                    list.add(RepoItem(o.getString("name"), normalizedType, itemPath, downloadUrl))
                }

                val nextPage = conn.getHeaderField("X-Next-Page")
                if (nextPage.isNullOrEmpty()) break
                page = nextPage.toIntOrNull() ?: break

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar arquivos (página $page)", e)
                break
            } finally {
                conn?.disconnect()
            }
        }

        return list
    }

    // ─── Download com notificação ─────────────────────────────────────────────

    private fun onItemClick(item: RepoItem) {
        (activity as? MainActivity)?.showBottomNavigation()
        if (item.type == "dir") {
            currentPath = item.path
            startFetch()
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = Pair(item.downloadUrl, item.name)
                requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                lifecycleScope.launch { downloadFile(item.downloadUrl, item.name) }
            }
        }
    }
    private fun createNotificationChannel() {
        // Evitando o crash do TODO("VERSION.SDK_INT < O") para APIs mais antigas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads de Provas",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso de download das provas"
            }
            val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private suspend fun downloadFile(downloadUrl: String, filename: String) {
        // Usando o contexto da aplicação para evitar vazamentos/erros se o Fragment for destruído durante o download
        val appContext = requireContext().applicationContext
        val notifId = notifIdCounter.getAndIncrement()
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Notificação de progresso
        val progressBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(filename)
            .setContentText("Baixando...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true) // indeterminate enquanto não temos tamanho

        nm.notify(notifId, progressBuilder.build())

        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Iniciando download: $downloadUrl")
                var url = URL(downloadUrl)
                var conn: HttpURLConnection
                var redirectCount = 0

                // Segue redirects mantendo o header de autenticação
                while (true) {
                    Log.d(TAG, "Conectando em: $url")
                    conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("PRIVATE-TOKEN", BuildConfig.GITLAB_PAT)
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.connect()

                    val code = conn.responseCode
                    Log.d(TAG, "Resposta HTTP: $code")

                    if (code in 301..308) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location.isNullOrEmpty() || redirectCount > 10) {
                            Log.e(TAG, "Redirect inválido ou loop")
                            notifyFailure(appContext, nm, notifId, filename)
                            return@withContext
                        }
                        url = URL(location)
                        redirectCount++
                        continue
                    }

                    if (code != HttpURLConnection.HTTP_OK) {
                        Log.e(TAG, "Erro HTTP final: $code")
                        conn.disconnect()
                        notifyFailure(appContext, nm, notifId, filename)
                        return@withContext
                    }

                    // HTTP 200 — grava o arquivo com progresso
                    val totalBytes = conn.contentLengthLong
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    val outputFile = java.io.File(downloadsDir, filename)

                    conn.inputStream.use { input ->
                        outputFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                // Atualiza progresso se soubermos o tamanho total
                                if (totalBytes > 0) {
                                    val percent = (totalRead * 100 / totalBytes).toInt()
                                    progressBuilder
                                        .setProgress(100, percent, false)
                                        .setContentText("$percent%")
                                    nm.notify(notifId, progressBuilder.build())
                                }
                            }
                        }
                    }
                    conn.disconnect()

                    Log.d(TAG, "Arquivo gravado em: ${outputFile.absolutePath}")

                    // O MediaScanner nos devolve uma URI content:// válida (no callback) para abrirmos o arquivo.
                    android.media.MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(outputFile.absolutePath),
                        null
                    ) { path, uri ->

                        val intent = if (uri != null) {
                            val extension = java.io.File(path).extension.lowercase()
                            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        } else {
                            // Fallback seguro caso a conversão de URI falhe (Abre a pasta de Downloads)
                            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        }

                        val pendingIntent = PendingIntent.getActivity(
                            appContext,
                            notifId,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        // Notificação de conclusão
                        val doneNotif = NotificationCompat.Builder(appContext, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle(filename)
                            .setContentText("Download concluído")
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setContentIntent(pendingIntent) // Vincula a ação de abrir o arquivo!
                            .setAutoCancel(true)
                            .build()

                        nm.notify(notifId, doneNotif)
                    }
                    break
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exceção no download de $filename", e)
                notifyFailure(appContext, nm, notifId, filename)
            }
        }
    }

    private fun notifyFailure(context: Context, nm: NotificationManager, notifId: Int, filename: String) {
        val failNotif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(filename)
            .setContentText("Falha no download")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(notifId, failNotif)
    }

    // ─── Filtro ───────────────────────────────────────────────────────────────

    private fun filterList(query: String?) {
        val lower = query?.lowercase() ?: ""
        adapter.updateData(allItems.filter { it.name.lowercase().contains(lower) })
    }

    // ─── Modelos e Adapter ────────────────────────────────────────────────────

    data class RepoItem(
        val name: String,
        val type: String,
        val path: String,
        val downloadUrl: String
    )

    private class ProvasAdapter(
        private var items: List<RepoItem>,
        private val listener: (RepoItem) -> Unit
    ) : RecyclerView.Adapter<ProvasAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_prova, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.text.text = item.name
            holder.icon.setImageResource(
                if (item.type == "dir") R.drawable.ic_folder else R.drawable.ic_file
            )
            holder.itemView.setOnClickListener { listener(item) }
        }

        override fun getItemCount() = items.size

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<RepoItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.item_icon)
            val text: TextView = itemView.findViewById(R.id.item_text)
        }
    }
}