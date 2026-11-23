package com.emergencycall.bypass.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.emergencycall.bypass.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val latestVersionTag: String,
    val currentVersionTag: String,
    val isUpdateAvailable: Boolean,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val htmlUrl: String,
    val publishedAt: String,
    val repoSuggestions: List<String>
)

object UpdateManager {

    private const val TAG = "UpdateManager"
    const val GITHUB_REPO = "neimargemini-blip/receptor-emergencia-dnd"
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /**
     * Verifica se há atualizações disponíveis no repositório GitHub.
     */
    suspend fun checkForUpdates(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_LATEST_RELEASE_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "EmergencyCallBypass-AndroidApp")
            }

            if (connection.responseCode != 200) {
                Log.w(TAG, "Falha ao consultar releases: HTTP ${connection.responseCode}")
                return@withContext null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val gson = Gson()
            val json = gson.fromJson(responseBody, JsonObject::class.java)

            val tagName = json.get("tag_name")?.asString ?: "v1.0"
            val releaseName = json.get("name")?.asString ?: tagName
            val releaseBody = json.get("body")?.asString ?: ""
            val htmlUrl = json.get("html_url")?.asString
                ?: "https://github.com/$GITHUB_REPO/releases"
            val publishedAt = json.get("published_at")?.asString ?: ""

            // Localiza a URL do APK compilado nos assets da release
            var apkUrl: String? = null
            if (json.has("assets") && json.get("assets").isJsonArray) {
                val assets = json.getAsJsonArray("assets")
                for (assetElement in assets) {
                    val asset = assetElement.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.get("browser_download_url")?.asString
                        break
                    }
                }
            }
            if (apkUrl == null) {
                apkUrl = "https://github.com/$GITHUB_REPO/releases/download/$tagName/Receptor-Emergencia-DND.apk"
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val isUpdateAvailable = isRemoteVersionNewer(tagName, currentVersion)

            val suggestions = extractOrGenerateSuggestions(releaseBody, tagName)

            AppUpdateInfo(
                latestVersionTag = tagName,
                currentVersionTag = "v$currentVersion",
                isUpdateAvailable = isUpdateAvailable,
                releaseName = releaseName,
                releaseNotes = releaseBody,
                apkDownloadUrl = apkUrl,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                repoSuggestions = suggestions
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao verificar atualizações do repositório", e)
            null
        }
    }

    /**
     * Compara versões semânticas (ex: v2.7 vs 2.6.0).
     * Retorna true se a versão remota for estritamente mais recente que a local.
     */
    fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
        try {
            val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (_: Exception) {
            return remoteTag != currentVersion
        }
    }

    /**
     * Extrai ou formata sugestões de repositório e melhores práticas para a nova versão.
     */
    private fun extractOrGenerateSuggestions(body: String, version: String): List<String> {
        val list = mutableListOf<String>()

        // Sugestões inteligentes do repositório
        list.add("⚡ Ative o 'Auto-Reinício se Fechado' e mantenha a bateria em 'Sem Restrições' para garantir funcionamento 24/7.")
        list.add("🚨 Teste a nova sirene de emergência e o piscar do flash na aba 'Demonstração' para familiarizar-se.")
        list.add("📍 Mantenha a permissão de Localização 'Permitir o tempo todo' para que os envios a cada 10s tenham precisão exata.")
        list.add("👥 Adicione contatos tanto na aba ENVIAR SOS quanto na aba RECEBER para máxima proteção mútua.")
        list.add("⭐ Acompanhe o código-fonte e envie feedbacks diretamente no repositório GitHub oficial.")

        return list
    }

    /**
     * Baixa o APK diretamente em segundo plano informando o progresso percentual.
     * Ao concluir, inicia automaticamente a tela de instalação do Android.
     */
    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = URL(apkUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "EmergencyCallBypass-AndroidApp")
            }

            // Suporta redirecionamentos do GitHub Releases (S3 AWS)
            var currentConnection = connection
            var responseCode = currentConnection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 307 || responseCode == 308
            ) {
                val newUrl = currentConnection.getHeaderField("Location")
                currentConnection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "EmergencyCallBypass-AndroidApp")
                }
                responseCode = currentConnection.responseCode
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                withContext(Dispatchers.Main) {
                    onError("Falha no download: Código HTTP $responseCode")
                }
                return@withContext
            }

            val fileLength = currentConnection.contentLength
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val outputFile = File(downloadDir, "Receptor-Emergencia-DND-update.apk")
            if (outputFile.exists()) {
                outputFile.delete()
            }

            currentConnection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    var lastPercent = -1

                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        output.write(data, 0, count)

                        if (fileLength > 0) {
                            val percent = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                onSuccess()
                launchApkInstallation(context, outputFile)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro durante download do APK", e)
            withContext(Dispatchers.Main) {
                onError("Erro no download: ${e.message ?: "Conexão perdida"}")
            }
        }
    }

    /**
     * Inicia a tela oficial do Android para instalar o pacote APK baixado.
     */
    fun launchApkInstallation(context: Context, apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao abrir instalador do APK", e)
            // Se falhar o instalador direto, abre a página de download no navegador
            openInBrowser(context, "https://github.com/$GITHUB_REPO/releases/latest")
        }
    }

    /**
     * Abre uma URL no navegador padrão.
     */
    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao abrir navegador", e)
        }
    }
}
