package org.arthur.pigeon

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.*
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import java.net.URLEncoder
import android.widget.Toast
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {
	private val requestNotificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Permissão de notificação negada", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback != null) {
            val resultUris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(resultUris)
            filePathCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
			}
		}

        // Inicia o serviço
        val serviceIntent = Intent(this, MessageCheckService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Configura o WebView
        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        val url = intent.getStringExtra("url") ?: "https://thepigeon.com.br/mobilee"
        webView.loadUrl(url)
		val sharedText = intent.getStringExtra("shared_text")
    	val sharedImage = intent.getStringExtra("shared_image")
		if (sharedImage != null) {
			webView.loadUrl("https://thepigeon.com.br/publish_protocol.php")
			webView.webViewClient = object : WebViewClient() {
				override fun onPageFinished(view: WebView?, url: String?) {
				    webView.evaluateJavascript("handleSharedImage('$sharedImage');", null)
				}
			}
		}

        checkForAppUpdate(this)
    }
	fun convertImageToBase64(uri: Uri): String {
		val inputStream = contentResolver.openInputStream(uri)
		val bytes = inputStream?.readBytes()
		val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
		return "data:image/png;base64,$base64"
	}

    fun checkForAppUpdate(context: Context) {
        Thread {
            try {
                val url = URL("https://thepigeon.com.br/APP/version.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val serverVersionCode = json.getInt("version_code")
                val apkUrl = json.getString("apk_url")

                val currentVersionCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).longVersionCode.toInt()

                if (serverVersionCode > currentVersionCode) {
                    Handler(Looper.getMainLooper()).post {
                        showUpdateDialog(this@MainActivity, apkUrl)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun showUpdateDialog(context: Context, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Atualização disponível")
            .setMessage("Há uma nova versão do Pigeon. Deseja atualizar agora?")
            .setPositiveButton("Baixar") { _, _ ->
                downloadAndInstallApk(context, apkUrl)
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    fun downloadAndInstallApk(context: Context, apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
        request.setTitle("Baixando atualização...")
        request.setDescription("Aguarde o download do novo Pigeon.")
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "update.apk")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == intent.action) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        val apkUri = Uri.parse(uriString)

                        val installIntent = Intent(Intent.ACTION_VIEW)
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                        installIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.startActivity(installIntent)
                    }
                    cursor.close()
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.getStringExtra("url")
        if (url != null) {
            webView.loadUrl(url)
        }
    }
}
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("text/")) {
                handleSendText(intent)
            } else if (type.startsWith("image/")) {
                handleSendImage(intent)
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null && type.startsWith("image/")) {
            handleSendMultipleImages(intent)
        } else {
            finish()
        }
    }

    private fun handleSendText(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText != null) {
            // Exemplo: carregar WebView com o texto compartilhado
            val myIntent = Intent(this, MainActivity::class.java)
            myIntent.putExtra("shared_text", sharedText)
            startActivity(myIntent)
        }
        finish()
    }

	private fun handleSendImage(intent: Intent) {
		val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
		if (imageUri != null) {
		    val inputStream = contentResolver.openInputStream(imageUri)
		    val bytes = inputStream?.readBytes()
		    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
		    val base64DataUri = "data:image/png;base64,$base64"

		    val myIntent = Intent(this, MainActivity::class.java)
		    myIntent.putExtra("shared_image", base64DataUri)
		    myIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
		    startActivity(myIntent)
		}
		finish()
	}

    private fun handleSendMultipleImages(intent: Intent) {
        val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!imageUris.isNullOrEmpty()) {
            val myIntent = Intent(this, MainActivity::class.java)
            myIntent.putExtra("shared_images", imageUris.map { it.toString() }.toTypedArray())
            startActivity(myIntent)
        }
        finish()
    }
}

