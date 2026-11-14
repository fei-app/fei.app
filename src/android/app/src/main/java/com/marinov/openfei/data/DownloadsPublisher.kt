package com.marinov.openfei.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Publica arquivos baixados em Download/OpenFEI/<subpasta>/ (Boletos, Moodle).
 *
 * - API 29+: MediaStore.Downloads (sem permissão de armazenamento).
 * - API 24–28: escrita direta em Download/OpenFEI/<subpasta> (exige WRITE_EXTERNAL_STORAGE).
 *
 * Se o mesmo arquivo (mesmo nome e mesmo tamanho) já estiver na pasta, ele é reaproveitado
 * em vez de gerar "arquivo (1).pdf". Devolve null se a publicação falhar; o chamador
 * decide o fallback (abrir direto do cache via [uriFileProvider]).
 */
object DownloadsPublisher {
    private const val TAG = "DownloadsPublisher"

    const val RAIZ = "OpenFEI"
    const val SUB_BOLETOS = "Boletos"
    const val SUB_MOODLE = "Moodle"

    /** true se, neste Android, ainda é preciso pedir WRITE_EXTERNAL_STORAGE (API <= 28). */
    fun precisaPermissaoLegado(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED

    fun uriFileProvider(context: Context, arquivo: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", arquivo)

    /** true se a Uri aponta para um arquivo em Download/OpenFEI (MediaStore ou legado). */
    fun foiParaDownloads(uri: Uri): Boolean =
        (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) ||
                uri.path?.contains("/downloads/$RAIZ/") == true

    fun publicar(
        context: Context,
        src: File,
        nome: String,
        mimeType: String,
        subpasta: String
    ): Uri? {
        val mime = if (mimeType.isBlank() || mimeType == "*/*") "application/octet-stream" else mimeType
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publicarMediaStore(context, src, nome, mime, subpasta)
            } else {
                publicarLegado(context, src, nome, mime, subpasta)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao publicar $nome em Download/$RAIZ/$subpasta", e)
            null
        }
    }

    private fun publicarMediaStore(
        context: Context,
        src: File,
        nome: String,
        mime: String,
        subpasta: String
    ): Uri? {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$RAIZ/$subpasta/"
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Já existe o mesmo arquivo (nome + tamanho) criado por este app? Reaproveita.
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.SIZE}=?",
            arrayOf(relativePath, nome, src.length().toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream retornou null")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null) // não deixa item "pending" órfão
            throw e
        }
    }

    private fun publicarLegado(
        context: Context,
        src: File,
        nome: String,
        mime: String,
        subpasta: String
    ): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$RAIZ/$subpasta"
        )
        if (!dir.exists() && !dir.mkdirs()) return null

        var dst = File(dir, nome)
        if (dst.exists() && dst.length() != src.length()) dst = nomeLivre(dir, nome)
        if (!dst.exists()) src.copyTo(dst)

        MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf(mime), null)
        return uriFileProvider(context, dst)
    }

    private fun nomeLivre(dir: File, nome: String): File {
        val base = nome.substringBeforeLast('.', nome)
        val ext = nome.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidato = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!candidato.exists()) return candidato
            i++
        }
    }
}