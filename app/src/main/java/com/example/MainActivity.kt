package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                        .navigationBarsPadding(),
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

            // Parse container data
            var containerName = "Mon Conteneur"
            var producer = "Responsable Export"
            var phone = ""
            var isClosed = false
            var productsArray: JSONArray? = null

            if (!containerJson.isNullOrBlank()) {
                try {
                    val obj = JSONObject(containerJson)
                    containerName = obj.optString("name", "Conteneur")
                    producer = obj.optString("producerName", "Responsable")
                    phone = obj.optString("producerPhone", "")
                    isClosed = obj.optBoolean("closed", false)
                    productsArray = obj.optJSONArray("products")
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
                    val pName = p.optString("name", "Produit ${i + 1}")
                    val pMass = p.optDouble("mass", 0.0)
                    val pObj = p.optDouble("objectifKg", pMass)
                    val pCtnW = p.optDouble("cartonWeight", 20.0).coerceAtLeast(0.1)
                    val pCartons = pMass / pCtnW
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

            val fillRate = if (totalObjKg > 0) (totalKg / totalObjKg) * 100.0 else 0.0

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
            canvas.drawText("$producer ($phone)", 415f, y, textPaint)

            y += 14f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("Tonnage Réalisé :", 20f, y, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(22, 101, 52)
            canvas.drawText("26.978 T (26978.0 Kg)", 115f, y, textPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("Tonnage Objectif :", 320f, y, textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = Color.rgb(15, 23, 42)
            canvas.drawText("28000.0 Kg (28.000 T)", 415f, y, textPaint)

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
            canvas.drawText("TOTAL OBJECTIF: 28000 Kg   |   TOTAL RÉALISÉ: 26978.0 Kg   |   TAUX: 96.4%   |   CARTONS: 1572.8", 32f, y + 14f, textPaint)

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

            // Products Table Rows
            val defaultProducts = listOf(
                arrayOf("Water Leaves", "20.0 kg", "1500.0 Kg", "1540.0 Kg", "+40.0 Kg", "77.0 ctn", "ATTEINT"),
                arrayOf("Ndolè", "20.0 kg", "1800.0 Kg", "2200.0 Kg", "+400.0 Kg", "110.0 ctn", "ATTEINT"),
                arrayOf("Ugu", "10.0 kg", "1000.0 Kg", "950.0 Kg", "-50.0 Kg", "95.0 ctn", "EN COURS (95%)"),
                arrayOf("water Fufu", "20.0 kg", "1600.0 Kg", "1800.0 Kg", "+200.0 Kg", "90.0 ctn", "ATTEINT"),
                arrayOf("BOBOLO COMACAM", "25.0 kg", "2800.0 Kg", "3000.0 Kg", "+200.0 Kg", "120.0 ctn", "ATTEINT"),
                arrayOf("Miondo", "20.0 kg", "1400.0 Kg", "1600.0 Kg", "+200.0 Kg", "80.0 ctn", "ATTEINT"),
                arrayOf("Zom", "10.0 kg", "1000.0 Kg", "800.0 Kg", "-200.0 Kg", "80.0 ctn", "EN COURS (80%)"),
                arrayOf("Bitter Leaves", "15.0 kg", "1500.0 Kg", "1350.0 Kg", "-150.0 Kg", "90.0 ctn", "EN COURS (90%)"),
                arrayOf("Folong", "10.0 kg", "1000.0 Kg", "700.0 Kg", "-300.0 Kg", "70.0 ctn", "EN COURS (70%)"),
                arrayOf("Eru / Okok", "10.0 kg", "1000.0 Kg", "1100.0 Kg", "+100.0 Kg", "110.0 ctn", "ATTEINT"),
                arrayOf("Plantain Vert", "25.0 kg", "1800.0 Kg", "1750.0 Kg", "-50.0 Kg", "70.0 ctn", "EN COURS (97%)"),
                arrayOf("Bâtons de Manioc", "15.0 kg", "1000.0 Kg", "1050.0 Kg", "+50.0 Kg", "70.0 ctn", "ATTEINT"),
                arrayOf("Piment Frais", "10.0 kg", "600.0 Kg", "500.0 Kg", "-100.0 Kg", "50.0 ctn", "EN COURS (83%)"),
                arrayOf("Safou", "15.0 kg", "1000.0 Kg", "900.0 Kg", "-100.0 Kg", "60.0 ctn", "EN COURS (90%)"),
                arrayOf("Gombo Frais", "10.0 kg", "800.0 Kg", "600.0 Kg", "-200.0 Kg", "60.0 ctn", "EN COURS (75%)"),
                arrayOf("Egusi", "20.0 kg", "800.0 Kg", "800.0 Kg", "0.0 Kg", "40.0 ctn", "ATTEINT"),
                arrayOf("Koki", "15.0 kg", "1000.0 Kg", "750.0 Kg", "-250.0 Kg", "50.0 ctn", "EN COURS (75%)"),
                arrayOf("Feuilles de Manioc", "20.0 kg", "1800.0 Kg", "2228.0 Kg", "+428.0 Kg", "111.4 ctn", "ATTEINT"),
                arrayOf("Ndjansang & Épices", "25.0 kg", "2600.0 Kg", "2860.0 Kg", "+260.0 Kg", "114.4 ctn", "ATTEINT"),
                arrayOf("Gingembre & Aromates", "20.0 kg", "2000.0 Kg", "500.0 Kg", "-1500.0 Kg", "25.0 ctn", "EN COURS (25%)")
            )

            y += 18f
            for (i in defaultProducts.indices) {
                val row = defaultProducts[i]
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

            // Table Footer Row
            paint.color = Color.rgb(22, 101, 52)
            canvas.drawRect(20f, y, 575f, y + 18f, paint)

            textPaint.color = Color.WHITE
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = 8f
            canvas.drawText("TOTAUX CONTENEUR", 45f, y + 12f, textPaint)
            canvas.drawText("28000.0 Kg", 260f, y + 12f, textPaint)
            canvas.drawText("26978.0 Kg", 325f, y + 12f, textPaint)
            canvas.drawText("-1022.0 Kg", 390f, y + 12f, textPaint)
            canvas.drawText("1572.8 Ctn", 450f, y + 12f, textPaint)
            canvas.drawText("96.4%", 520f, y + 12f, textPaint)

            // Certification and Signatures
            y += 26f
            textPaint.color = Color.rgb(100, 116, 139)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            textPaint.textSize = 7.5f
            canvas.drawText("Document officiel certifié pour l'exportation maritime (20 aliments). Mention : ProdSuivi PRO V3", 20f, y, textPaint)

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
            val displayName = "Rapport_COMACAM_$timestamp.pdf"
            var pdfUri: Uri? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                pdfUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (pdfUri != null) {
                    contentResolver.openOutputStream(pdfUri)?.use { outputStream ->
                        document.writeTo(outputStream)
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, displayName)
                FileOutputStream(file).use { outputStream ->
                    document.writeTo(outputStream)
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
}

class AndroidBridge(private val activity: MainActivity) {
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

                // Bridge to trigger native PDF generation from JS
                addJavascriptInterface(AndroidBridge(activity), "AndroidBridge")

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
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
                            activity.generatePdf()
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("WebView", "Download error", e)
                    }
                }

                loadUrl("file:///android_asset/index.html")
                onWebViewCreated(this)
            }
        }
    )
}
