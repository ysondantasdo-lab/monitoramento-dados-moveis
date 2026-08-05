package com.ysondantas.monitordados
// ⚠️ Pacote não encontrado em build.gradle existente -> usando padrão "com.ysondantas.monitordados".
// Se seu projeto já tiver outro applicationId/namespace, apenas troque esta linha
// (e o "package=" do AndroidManifest.xml) para o nome real do seu projeto.

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ---------- UI ----------
    private lateinit var tvStatus: TextView
    private lateinit var tvCellInfo: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLogPath: TextView // 🆕 Adicione esta linha aqui

    // ---------- Controle de atualização ----------
    private val handler = Handler(Looper.getMainLooper())
    private val UPDATE_INTERVAL_MS = 2500L // 2.5s (entre 2 e 3s pedidos)

    // ---------- TrafficStats ----------
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTimestamp = 0L

    // ---------- Estado anterior (para detectar mudanças/quedas) ----------
    private var lastCellId: Int? = null
    private var wasMobileConnected = true
    private var wasSignalOk = true

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var connectivityManager: ConnectivityManager

    private val REQUEST_CODE_PERMISSIONS = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setContentView(buildLayout())

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE),
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Inicializa contadores de tráfego
        lastRxBytes = TrafficStats.getMobileRxBytes()
        lastTxBytes = TrafficStats.getMobileTxBytes()
        lastTimestamp = System.currentTimeMillis()
    }

    // Cria a UI simples 100% em código (sem XML de layout)
    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            gravity = Gravity.TOP
        }

        tvStatus = TextView(this).apply {
            textSize = 20f
            setPadding(0, 0, 0, 40)
        }
        tvCellInfo = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        tvSignal = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        tvSpeed = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        tvLogPath = TextView(this).apply {
            textSize = 14f
            setPadding(0, 60, 0, 0) // Espaçamento maior para ficar no rodapé da tela
            text = "📁 Histórico salvo em:\nArmazenamento Interno > Download > Errodadosmoveis > log_erros_moveis.txt"
        }

        

        root.addView(tvStatus)
        root.addView(tvCellInfo)
        root.addView(tvSignal)
        root.addView(tvSpeed)
        root.addView(tvLogPath) // 🆕 Adiciona o campo informativo ao layout da tela
        return root
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    // ---------- Ciclo de vida: SOMENTE PRIMEIRO PLANO ----------
    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Para completamente ao sair de primeiro plano (sem Service/WorkManager)
        handler.removeCallbacks(updateRunnable)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateScreen()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    // ---------- Lógica principal ----------
    private fun updateScreen() {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        if (isWifi) {
            tvStatus.text = "Wi-Fi Ativo - Monitoramento Pausado"
            tvCellInfo.text = ""
            tvSignal.text = ""
            tvSpeed.text = ""
            // Reseta baseline de tráfego para não computar "salto" quando voltar ao 4G/5G
            lastRxBytes = TrafficStats.getMobileRxBytes()
            lastTxBytes = TrafficStats.getMobileTxBytes()
            lastTimestamp = System.currentTimeMillis()
            wasMobileConnected = false
            return
        }

        tvStatus.text = "Monitorando Rede Móvel"

        if (!isMobile) {
            // Perdeu conexão de dados móveis por completo
            if (wasMobileConnected) {
                writeLog(this, "Queda de conexão (sem rede móvel ativa)")
            }
            wasMobileConnected = false
            tvCellInfo.text = "Antena: sem conexão"
            tvSignal.text = "Sinal: --"
            tvSpeed.text = "Velocidade: 0 KB/s ↓ / 0 KB/s ↑"
            return
        }
        wasMobileConnected = true

        // ---- Cell ID / TAC / dBm ----
        readCellInfo()

        // ---- Velocidade via TrafficStats ----
        readTrafficSpeed()
    }

    private fun readCellInfo() {
        if (!hasPermissions()) {
            tvCellInfo.text = "Antena: permissão de localização necessária"
            tvSignal.text = "Sinal: --"
            return
        }

        try {
            val cellInfoList = telephonyManager.allCellInfo
            val registeredCell = cellInfoList?.firstOrNull {
                (it.isRegistered)
            }

            if (registeredCell == null) {
                tvCellInfo.text = "Antena: nenhuma célula registrada"
                tvSignal.text = "Sinal: --"
                if (wasSignalOk) {
                    writeLog(this, "Sinal sumiu (nenhuma célula registrada)")
                }
                wasSignalOk = false
                return
            }

            var cellId: Int? = null
            var tac: Int? = null
            var dbm: Int? = null
            var sinr: Int? = null // Nova métrica crucial para o diagnóstico
            var typeLabel = "Desconhecido"

            when (registeredCell) {
                is CellInfoNr -> { // 5G Nativo (Sem Reflection)
                    val identity = registeredCell.cellIdentity as android.telephony.CellIdentityNr
                    val ss = registeredCell.cellSignalStrength as android.telephony.CellSignalStrengthNr
                    
                    dbm = ss.dbm
                    sinr = if (ss.ssSinr != android.telephony.CellInfo.UNAVAILABLE) ss.ssSinr else null
                    typeLabel = "NR (5G)"
                    
                    tac = if (identity.tac != android.telephony.CellInfo.UNAVAILABLE) identity.tac else null
                    val nci = identity.nci
                    cellId = if (nci != android.telephony.CellInfo.UNAVAILABLE_LONG) (nci and 0xFFFFFFF).toInt() else null
                }
                is CellInfoLte -> { // 4G LTE com ruído de sinal
                    val identity = registeredCell.cellIdentity
                    val ss = registeredCell.cellSignalStrength
                    
                    dbm = ss.dbm
                    sinr = if (ss.rssnr != android.telephony.CellInfo.UNAVAILABLE) ss.rssnr else null
                    typeLabel = "LTE (4G)"
                    
                    tac = if (identity.tac != android.telephony.CellInfo.UNAVAILABLE) identity.tac else null
                    cellId = if (identity.ci != android.telephony.CellInfo.UNAVAILABLE) identity.ci else null
                }
                is CellInfoWcdma -> {
                    cellId = registeredCell.cellIdentity.cid
                    tac = registeredCell.cellIdentity.lac
                    dbm = registeredCell.cellSignalStrength.dbm
                    typeLabel = "WCDMA (3G)"
                }
                is CellInfoGsm -> {
                    cellId = registeredCell.cellIdentity.cid
                    tac = registeredCell.cellIdentity.lac
                    dbm = registeredCell.cellSignalStrength.dbm
                    typeLabel = "GSM (2G)"
                }
            }


            tvCellInfo.text = "Antena [$typeLabel]: Cell ID = ${cellId ?: "--"} | TAC/LAC = ${tac ?: "--"}"
            tvSignal.text = "Sinal: ${dbm ?: "--"} dBm"

            // Log: troca de antena
            if (cellId != null && lastCellId != null && cellId != lastCellId) {
                writeLog(this, "Troca de Antena para ID $cellId")
            }
            if (cellId != null) lastCellId = cellId

            // Log: sinal fraco
            if (dbm != null) {
                if (dbm < -110) {
                    if (wasSignalOk) {
                        writeLog(this, "Sinal fraco abaixo de -110dBm (atual: $dbm dBm)")
                    }
                    wasSignalOk = false
                } else {
                    wasSignalOk = true
                }
            }

        } catch (e: SecurityException) {
            tvCellInfo.text = "Antena: permissão negada"
            tvSignal.text = "Sinal: --"
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro ao ler CellInfo", e)
        }
    }

    private fun readTrafficSpeed() {
        val currentRx = TrafficStats.getMobileRxBytes()
        val currentTx = TrafficStats.getMobileTxBytes()
        val now = System.currentTimeMillis()

        val elapsedSeconds = ((now - lastTimestamp).coerceAtLeast(1)) / 1000.0

        val rxDiff = (currentRx - lastRxBytes).coerceAtLeast(0)
        val txDiff = (currentTx - lastTxBytes).coerceAtLeast(0)

        val downloadKBs = (rxDiff / elapsedSeconds) / 1024.0
        val uploadKBs = (txDiff / elapsedSeconds) / 1024.0

        tvSpeed.text = "Velocidade: %.1f KB/s ↓ / %.1f KB/s ↑".format(downloadKBs, uploadKBs)

        // 🛑 NOVA LÓGICA DE TELECOM: DETECTAR TRAVAMENTO LÓGICO (DATA STALLING)
        // Se o download está zerado, mas o sinal de rede está ativo (wasSignalOk) E 
        // existe alguma atividade de upload (txDiff > 0), significa que o seu S24 está tentando 
        // transmitir pacotes para a Claro, mas a rede não está respondendo com dados de volta.
        if (downloadKBs <= 0.0) {
            if (wasSignalOk && txDiff > 0) {
                writeLog(this, "🚨 DATA STALLING: Upload enviando pacotes ($txDiff bytes), mas rede Claro retornou 0 KB/s de Download. Antena ativa: $lastCellId")
            }
        }

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimestamp = now
    }


    // ---------- Log de falhas via MediaStore (Download/Errodadosmoveis) ----------
        // Adicione as duas funções de classificação AQUI, logo antes do writeLog:
    
    // Função auxiliar para classificar a força do sinal (dBm)
    private fun getSignalStrengthLabel(dbm: Int?): String {
        if (dbm == null || dbm == -999) return "Sem Sinal"
        return when {
            dbm >= -85 -> "Excelente 🟢"
            dbm >= -95 -> "Bom 🟡"
            dbm >= -105 -> "Regular 🟠"
            else -> "Ruim 🔴"
        }
    }

    // Função auxiliar para classificar a qualidade/ruído do sinal (SINR)
    private fun getSignalQualityLabel(sinr: Int?): String {
        if (sinr == null) return ""
        return when {
            sinr >= 13 -> " (Limpo ✨)"
            sinr >= 5  -> " (Normal 👍)"
            sinr >= 0  -> " (Instável ⚠️)"
            else       -> " (Muita Interferência 🛑)"
        }
    }

    // ---------- Log de falhas via MediaStore (Download/Errodadosmoveis) ----------
    private fun writeLog(context: Context, message: String) {
        try {
            val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            // ... (resto do seu código original do writeLog que você enviou)

    
    private fun writeLog(context: Context, message: String) {
        try {
            val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val logLine = "[$timestamp] -> $message\n"

            val resolver = context.contentResolver
            val fileName = "log_erros_moveis.txt"
            val relativePath = "Download/Errodadosmoveis/"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            // Verifica se o arquivo já existe nessa pasta pública
            var existingUri: Uri? = null
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, relativePath)

            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    existingUri = ContentUris.withAppendedId(collection, id)
                }
            }

            val targetUri: Uri? = existingUri ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                resolver.insert(collection, values)
            }

            targetUri?.let { uri ->
                // Modo "wa" = write + append -> nunca sobrescreve o conteúdo anterior
                resolver.openOutputStream(uri, "wa")?.use { stream ->
                    stream.write(logLine.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro ao gravar log em MediaStore", e)
        }
    }
}
