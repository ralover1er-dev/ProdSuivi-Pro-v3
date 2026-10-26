package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    var lastContainerJson: String? = null
    var pendingImportJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePdfIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView?.let { wv ->
                    wv.evaluateJavascript(
                        """
                        (function() {
                            if (typeof currentView !== 'undefined') {
                                if (currentView === 'product-detail') {
                                    navigateTo('container-detail', currentContainerId);
                                    return true;
                                } else if (currentView === 'container-detail') {
                                    navigateTo('containers');
                                    return true;
                                } else if (currentView === 'profile' || currentView === 'containers') {
                                    navigateTo('dashboard');
                                    return true;
                                }
                            }
                            return false;
                        })();
                        """.trimIndent()
                    ) { result ->
                        if (result != "true") {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                } ?: run {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WebAppScreen(
                        activity = this,
                        onWebViewCreated = { wv ->
                            webView = wv
                        }
                    )
                }
            }
        }
    }

    /**
     * Native PDF generation using android.graphics.pdf.PdfDocument
     * Saves to MediaStore.Downloads.EXTERNAL_CONTENT_URI on Android Q+ (Android 10..14+)
     * and opens via Intent.ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun generatePdf(containerJson: String? = null) {
        if (!containerJson.isNullOrBlank()) {
            lastContainerJson = containerJson
        }
        val jsonToParse = if (!containerJson.isNullOrBlank()) containerJson else lastContainerJson

        // Parse container data (dynamique sans aucun mock data)
        var containerName = "Mon Conteneur"
        var producer = "Responsable Export"
        var phone = ""
        var isClosed = false
        var productsArray: JSONArray? = null

        if (!jsonToParse.isNullOrBlank()) {
            try {
                val obj = JSONObject(jsonToParse)
                containerName = if (obj.has("nom") && !obj.optString("nom").isNullOrBlank()) {
                    obj.optString("nom")
                } else {
                    obj.optString("name", "Mon Conteneur")
                }
                producer = if (obj.has("producerName") && !obj.optString("producerName").isNullOrBlank()) {
                    obj.optString("producerName")
                } else if (obj.has("producteur") && !obj.optString("producteur").isNullOrBlank()) {
                    obj.optString("producteur")
                } else if (obj.has("responsable") && !obj.optString("responsable").isNullOrBlank()) {
                    obj.optString("responsable")
                } else {
                    "Responsable Export"
                }
                phone = if (obj.has("producerPhone")) {
                    obj.optString("producerPhone", "")
                } else {
                    obj.optString("telephone", "")
                }
                isClosed = obj.optBoolean("closed", obj.optBoolean("cloture", false))
                productsArray = obj.optJSONArray("listeProduits") ?: obj.optJSONArray("products")
            } catch (e: Exception) {
                Log.w("MainActivity", "Error parsing container json", e)
            }
        }

        val productList = mutableListOf<Array<String>>()
        var totalKg = 0.0
        var totalObjKg = 0.0
        var totalCartons = 0.0

        if (productsArray != null && productsArray.length() > 0) {
            for (i in 0 until productsArray.length()) {
                val p = productsArray.getJSONObject(i)
                val pName = if (p.has("nom") && !p.optString("nom").isNullOrBlank()) {
                    p.optString("nom")
                } else {
                    p.optString("name", "Produit ${i + 1}")
                }
                val pMass = if (p.has("mass")) p.optDouble("mass", 0.0)
                    else if (p.has("realise")) p.optDouble("realise", 0.0)
                    else if (p.has("poids")) p.optDouble("poids", 0.0)
                    else 0.0
                val pObj = if (p.has("objectifKg")) p.optDouble("objectifKg", pMass)
                    else if (p.has("objectif")) p.optDouble("objectif", pMass)
                    else pMass
                val pCtnW = if (p.has("cartonWeight")) p.optDouble("cartonWeight", 20.0).coerceAtLeast(0.1)
                    else if (p.has("poidsCtn")) p.optDouble("poidsCtn", 20.0).coerceAtLeast(0.1)
                    else 20.0
                val pCartons = if (pCtnW > 0) pMass / pCtnW else 0.0
                val diff = pMass - pObj
                val sign = if (diff >= 0) "+" else ""
                val isAtteint = pMass >= pObj
                val statusStr = if (isAtteint) "ATTEINT" else "EN COURS (${if (pObj > 0) ((pMass / pObj) * 100).toInt() else 0}%)"

                totalKg += pMass
                totalObjKg += pObj
                totalCartons += pCartons

                productList.add(
                    arrayOf(
                        pName,
                        String.format(Locale.US, "%.1f kg", pCtnW),
                        String.format(Locale.US, "%.1f Kg", pObj),
                        String.format(Locale.US, "%.1f Kg", pMass),
                        String.format(Locale.US, "%s%.1f Kg", sign, diff),
                        String.format(Locale.US, "%.1f ctn", pCartons),
                        statusStr
                    )
                )
            }
        }

        val totalTonnes = totalKg / 1000.0
        val totalObjTonnes = totalObjKg / 1000.0
        val fillRate = if (totalObjKg > 0) (totalKg / totalObjKg) * 100.0 else 0.0

        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val paint = Paint()
            val textPaint = Paint().apply {
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            // Header Banner (Agro Deep Green #166534)
            paint.color = Color.rgb(22, 101, 52)
            canvas.drawRect(0f, 0f, 595f, 65f, paint)

            textPaint.color = Color.WHITE
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 15f
            canvas.drawText("RAPPORT DE CONTENEUR ALIMENTAIRE", 20f, 26f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.textSize = 9.5f
            canvas.drawText("ProdSuivi PRO V3 - Agro-Logistique Alimentaire Cameroun", 20f, 40f, textPaint)
            textPaint.textSize = 8.5f
            canvas.drawText("Suivi de Production & Objectifs d'Exportation Maritime - Port de Douala", 20f, 53f, textPaint)

            // Status Badge
            if (isClosed) {
                paint.color = Color.rgb(185, 28, 28)
                canvas.drawRoundRect(465f, 16f, 575f, 42f, 8f, 8f, paint)
                textPaint.color = Color.WHITE
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.textSize = 8.5f
                canvas.drawText("STATUT : CLÔTURÉ", 475f, 32f, textPaint)
            } else {
                paint.color = Color.rgb(21, 128, 61)
                canvas.drawRoundRect(465f, 16f, 575f, 42f, 8f, 8f, paint)
                textPaint.color = Color.WHITE
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.textSize = 8.5f
                canvas.drawText("STATUT : OUVERT", 480f, 32f, textPaint)
            }

            // Shipping Info Header
            var y = 84f
            textPaint.color = Color.rgb(30, 41, 59)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 10f
            canvas.drawText("FICHE EXPÉDITION CONTENEUR EXPORT", 20f, y, textPaint)

            paint.color = Color.rgb(226, 232, 240)
            paint.strokeWidth = 1f
            canvas.drawLine(20f, y + 4f, 575f, y + 4f, paint)

            y += 18f
            textPaint.textSize = 8.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("Nom du Conteneur :", 20f, y, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            canvas.drawText(containerName, 115f, y, textPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("Producteur / Resp. :", 320f, y, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            val respDisplay = if (phone.isNotBlank()) "$producer ($phone)" else producer
            canvas.drawText(respDisplay, 415f, y, textPaint)

            y += 14f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("Tonnage Réalisé :", 20f, y, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(22, 101, 52)
            canvas.drawText(String.format(Locale.US, "%.3f T (%.1f Kg)", totalTonnes, totalKg), 115f, y, textPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("Tonnage Objectif :", 320f, y, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            canvas.drawText(String.format(Locale.US, "%.1f Kg (%.3f T)", totalObjKg, totalObjTonnes), 415f, y, textPaint)

            // Green Highlight Banner
            y += 12f
            paint.color = Color.rgb(240, 253, 244)
            canvas.drawRoundRect(20f, y, 575f, y + 22f, 6f, 6f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(187, 247, 208)
            canvas.drawRoundRect(20f, y, 575f, y + 22f, 6f, 6f, paint)
            paint.style = Paint.Style.FILL

            textPaint.color = Color.rgb(22, 101, 52)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 9f
            canvas.drawText(
                String.format(
                    Locale.US,
                    "TOTAL OBJECTIF: %.0f Kg   |   TOTAL RÉALISÉ: %.1f Kg   |   TAUX: %.1f%%   |   CARTONS: %.1f",
                    totalObjKg, totalKg, fillRate, totalCartons
                ),
                32f, y + 14f, textPaint
            )

            // Products Table Header
            y += 30f
            paint.color = Color.rgb(22, 101, 52)
            canvas.drawRect(20f, y, 575f, y + 18f, paint)

            textPaint.color = Color.WHITE
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 7.5f
            canvas.drawText("#", 25f, y + 12f, textPaint)
            canvas.drawText("Produit", 45f, y + 12f, textPaint)
            canvas.drawText("Poids Ctn", 200f, y + 12f, textPaint)
            canvas.drawText("Objectif", 260f, y + 12f, textPaint)
            canvas.drawText("Réalisé", 325f, y + 12f, textPaint)
            canvas.drawText("Écart", 390f, y + 12f, textPaint)
            canvas.drawText("Cartons", 450f, y + 12f, textPaint)
            canvas.drawText("Statut", 515f, y + 12f, textPaint)

            // Products Table Rows (Entièrement dynamiques - aucun mock data)
            y += 18f
            if (productList.isEmpty()) {
                paint.color = Color.rgb(248, 250, 252)
                canvas.drawRect(20f, y, 575f, y + 22f, paint)
                textPaint.color = Color.rgb(100, 116, 139)
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                textPaint.textSize = 8f
                canvas.drawText("Aucun produit enregistré dans ce conteneur", 45f, y + 14f, textPaint)
                y += 22f
            } else {
                for (i in productList.indices) {
                    val row = productList[i]
                    if (i % 2 == 1) {
                        paint.color = Color.rgb(248, 250, 252)
                        canvas.drawRect(20f, y, 575f, y + 17f, paint)
                    }

                    textPaint.color = Color.rgb(51, 65, 85)
                    textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    textPaint.textSize = 7.5f

                    canvas.drawText("${i + 1}", 25f, y + 11.5f, textPaint)

                    textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    canvas.drawText(row[0], 45f, y + 11.5f, textPaint)

                    textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    canvas.drawText(row[1], 200f, y + 11.5f, textPaint)
                    canvas.drawText(row[2], 260f, y + 11.5f, textPaint)
                    canvas.drawText(row[3], 325f, y + 11.5f, textPaint)
                    canvas.drawText(row[4], 390f, y + 11.5f, textPaint)
                    canvas.drawText(row[5], 450f, y + 11.5f, textPaint)

                    if (row[6].startsWith("ATTEINT")) {
                        textPaint.color = Color.rgb(22, 101, 52)
                        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    } else {
                        textPaint.color = Color.rgb(180, 83, 9)
                    }
                    canvas.drawText(row[6], 515f, y + 11.5f, textPaint)

                    y += 17f
                }
            }

            // Table Footer Row
            paint.color = Color.rgb(22, 101, 52)
            canvas.drawRect(20f, y, 575f, y + 18f, paint)

            textPaint.color = Color.WHITE
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 8f
            val diffTotal = totalKg - totalObjKg
            val signTotal = if (diffTotal >= 0) "+" else ""

            canvas.drawText("TOTAUX CONTENEUR", 45f, y + 12f, textPaint)
            canvas.drawText(String.format(Locale.US, "%.1f Kg", totalObjKg), 260f, y + 12f, textPaint)
            canvas.drawText(String.format(Locale.US, "%.1f Kg", totalKg), 325f, y + 12f, textPaint)
            canvas.drawText(String.format(Locale.US, "%s%.1f Kg", signTotal, diffTotal), 390f, y + 12f, textPaint)
            canvas.drawText(String.format(Locale.US, "%.1f Ctn", totalCartons), 450f, y + 12f, textPaint)
            canvas.drawText(String.format(Locale.US, "%.1f%%", fillRate), 520f, y + 12f, textPaint)

            // Certification and Signatures
            y += 26f
            textPaint.color = Color.rgb(100, 116, 139)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            textPaint.textSize = 7.5f
            canvas.drawText(
                "Document officiel certifié pour l'exportation maritime (${productList.size} aliment${if (productList.size > 1) "s" else ""}). Mention : ProdSuivi PRO V3",
                20f, y, textPaint
            )

            y += 8f
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(203, 213, 225)
            canvas.drawRoundRect(20f, y, 220f, y + 42f, 4f, 4f, paint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.textSize = 7.5f
            textPaint.color = Color.rgb(71, 85, 105)
            canvas.drawText("Visa Superviseur Chargement", 28f, y + 14f, textPaint)

            canvas.drawRoundRect(355f, y, 575f, y + 42f, 4f, 4f, paint)
            canvas.drawText("Cachet Phyto-Sanitaire Port de Douala", 365f, y + 14f, textPaint)

            document.finishPage(page)

            // Save PDF to Downloads via MediaStore on Android Q+, or direct file on older
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeContName = containerName.replace(Regex("[^a-zA-Z0-9_]"), "_").take(25)
            val displayName = "Rapport_${safeContName}_$timestamp.pdf"
            var pdfUri: Uri? = null

            val byteStream = java.io.ByteArrayOutputStream()
            document.writeTo(byteStream)
            val rawPdfBytes = byteStream.toByteArray()
            val finalPdfBytes = if (!jsonToParse.isNullOrBlank()) {
                val tag = "\n%###PRODSUIVI_DATA###$jsonToParse###END###\n"
                rawPdfBytes + tag.toByteArray(Charsets.UTF_8)
            } else {
                rawPdfBytes
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                pdfUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (pdfUri != null) {
                    contentResolver.openOutputStream(pdfUri)?.use { outputStream ->
                        outputStream.write(finalPdfBytes)
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, displayName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(finalPdfBytes)
                }
                pdfUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
            }

            if (pdfUri != null) {
                Toast.makeText(this, "PDF enregistré dans Téléchargements", Toast.LENGTH_LONG).show()

                // Open with Intent.ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(pdfUri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(Intent.createChooser(viewIntent, "Ouvrir le Rapport PDF"))
                } catch (e: Exception) {
                    Log.w("MainActivity", "No app available to view PDF", e)
                }
            } else {
                throw IOException("Échec de création du fichier PDF dans le stockage")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur lors de la génération du PDF", e)
            Toast.makeText(this, "Erreur génération PDF : ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            document.close()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePdfIntent(intent)
    }

    fun onWebViewPageFinished(view: WebView?) {
        deliverPendingImport()
    }

    private fun deliverPendingImport() {
        val json = pendingImportJson ?: return
        webView?.let { wv ->
            runOnUiThread {
                val escaped = JSONObject.quote(json)
                wv.evaluateJavascript("if (typeof window.importerConteneurPartenaire === 'function') { window.importerConteneurPartenaire($escaped); }", null)
                pendingImportJson = null
            }
        }
    }

    private fun handlePdfIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uri = intent.data

        if (Intent.ACTION_VIEW == action && uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val text = String(bytes, Charsets.ISO_8859_1)
                    val startTag = "###PRODSUIVI_DATA###"
                    val endTag = "###END###"
                    val startIdx = text.indexOf(startTag)
                    if (startIdx != -1) {
                        val endIdx = text.indexOf(endTag, startIdx + startTag.length)
                        if (endIdx != -1) {
                            val rawJson = text.substring(startIdx + startTag.length, endIdx)
                            val utf8Json = String(rawJson.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                            pendingImportJson = utf8Json
                            deliverPendingImport()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Erreur lecture PDF importé", e)
            }
        }
    }

    fun saveAndSharePdf(base64Data: String?, fileName: String?, containerJson: String?) {
        try {
            val name = if (!fileName.isNullOrBlank()) fileName else "Rapport_ProdSuivi_${System.currentTimeMillis()}.pdf"
            val cacheFile = File(cacheDir, name)

            if (!base64Data.isNullOrBlank()) {
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val textContent = String(decodedBytes, Charsets.ISO_8859_1)
                val finalBytes = if (!textContent.contains("###PRODSUIVI_DATA###") && !containerJson.isNullOrBlank()) {
                    val hiddenPayload = "\n%###PRODSUIVI_DATA###$containerJson###END###\n"
                    decodedBytes + hiddenPayload.toByteArray(Charsets.UTF_8)
                } else {
                    decodedBytes
                }
                FileOutputStream(cacheFile).use { it.write(finalBytes) }
            } else {
                generatePdf(containerJson)
                return
            }

            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", cacheFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Partager le Rapport PDF"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur saveAndSharePdf", e)
            Toast.makeText(this, "Erreur partage PDF: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}

class AndroidShare(private val activity: MainActivity) {
    @JavascriptInterface
    fun sharePDF(base64Data: String?, fileName: String?, containerJson: String?) {
        activity.runOnUiThread {
            activity.saveAndSharePdf(base64Data, fileName, containerJson)
        }
    }
}

class AndroidBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun isAppActivated(): Boolean {
        val prefs = activity.getSharedPreferences("prodsuivi_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("@app_activee", false)
    }

    @JavascriptInterface
    fun setAppActivated(activated: Boolean) {
        val prefs = activity.getSharedPreferences("prodsuivi_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("@app_activee", activated).apply()
    }

    @JavascriptInterface
    fun updateActiveContainer(containerJson: String?) {
        if (!containerJson.isNullOrBlank()) {
            activity.lastContainerJson = containerJson
        }
    }

    @JavascriptInterface
    fun generatePdf(containerJson: String?) {
        activity.runOnUiThread {
            activity.generatePdf(containerJson)
        }
    }

    @JavascriptInterface
    fun showToast(msg: String?) {
        activity.runOnUiThread {
            if (!msg.isNullOrBlank()) {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppScreen(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Use software layer type to avoid Mesa rendernode errors in emulators/cloud containers
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    displayZoomControls = false
                    builtInZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                // Bridges for native features and partner PDF sharing
                addJavascriptInterface(AndroidBridge(activity), "AndroidBridge")
                addJavascriptInterface(AndroidShare(activity), "AndroidShare")

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        activity.onWebViewPageFinished(view)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        Log.w("MainActivity", "Renderer process exited gracefully (didCrash=${detail?.didCrash()})")
                        view?.let { wv ->
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("file:///android_asset/")) {
                            return false
                        }
                        return try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                }

                setDownloadListener { url, _, _, mimetype, _ ->
                    try {
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            activity.generatePdf(activity.lastContainerJson)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("WebView", "Download error", e)
                    }
                }

                val urlToLoad = try {
                    val publicList = context.assets.list("public")
                    if (publicList != null && publicList.contains("index.html")) {
                        "file:///android_asset/public/index.html"
                    } else {
                        "file:///android_asset/index.html"
                    }
                } catch (e: Exception) {
                    "file:///android_asset/index.html"
                }
                loadUrl(urlToLoad)
                onWebViewCreated(this)
            }
        }
    )
}
