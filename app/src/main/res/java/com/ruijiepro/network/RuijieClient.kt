package com.ruijiepro.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.ruijiepro.network.models.*
import com.ruijiepro.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class RuijieClient(private val prefs: PreferencesManager) {

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val TAG = "RuijieClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val clientFollowRedirects = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    var gatewayIp: String? = prefs.gatewayIp
    var gatewayMac: String? = prefs.gatewayMac
    var sessionId: String? = null

    val logBuilder = StringBuilder()
    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logBuilder.appendLine(msg)
        onLog?.invoke(msg)
    }

    // ─── Step 1: Detect Gateway ─────────────────────────────────────────────

    suspend fun detectGateway(): Result<GatewayInfo> = withContext(Dispatchers.IO) {
        log("[*] Detecting gateway via connectivitycheck.gstatic.com...")
        val request = Request.Builder()
            .url("http://connectivitycheck.gstatic.com/generate_204")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()

        try {
            val response = client.newCall(request).execute()
            val status = response.code
            log("[*] Gateway probe status: $status")

            if (status in listOf(301, 302, 307, 308)) {
                val location = response.header("Location") ?: ""
                log("[*] Redirect location: $location")
                val uri = URI(location)
                val params = parseQueryParams(uri.query ?: "")

                val ip = params["gw_address"]
                val mac = params["mac"] ?: params["umac"] ?: params["usermac"]

                if (!ip.isNullOrBlank()) {
                    gatewayIp = ip
                    prefs.gatewayIp = ip
                    log("[+] Gateway IP: $ip")
                }
                if (!mac.isNullOrBlank()) {
                    gatewayMac = mac
                    prefs.gatewayMac = mac
                    log("[+] MAC: $mac")
                }

                if (!gatewayIp.isNullOrBlank() && !gatewayMac.isNullOrBlank()) {
                    return@withContext Result.success(GatewayInfo(gatewayIp!!, gatewayMac!!))
                }
            }

            // Fallback to saved
            if (prefs.hasSavedGateway()) {
                gatewayIp = prefs.gatewayIp
                gatewayMac = prefs.gatewayMac
                log("[+] Using saved gateway: IP=${gatewayIp}, MAC=${gatewayMac}")
                return@withContext Result.success(GatewayInfo(gatewayIp!!, gatewayMac!!))
            }

            Result.failure(Exception("Gateway detection failed. Not on a captive portal network."))
        } catch (e: Exception) {
            log("[!] Gateway probe error: ${e.message}")
            if (prefs.hasSavedGateway()) {
                gatewayIp = prefs.gatewayIp
                gatewayMac = prefs.gatewayMac
                log("[+] Using saved gateway (fallback): IP=${gatewayIp}")
                return@withContext Result.success(GatewayInfo(gatewayIp!!, gatewayMac!!))
            }
            Result.failure(Exception("Gateway error: ${e.message}"))
        }
    }

    // ─── Step 2: Fetch Session ID ────────────────────────────────────────────

    suspend fun fetchSessionId(): Result<String> = withContext(Dispatchers.IO) {
        val ip = gatewayIp ?: return@withContext Result.failure(Exception("No gateway IP"))
        val mac = gatewayMac ?: return@withContext Result.failure(Exception("No gateway MAC"))

        log("[*] Fetching session ID...")

        val step1Url = "https://portal-as.ruijienetworks.com/auth/wifidogAuth/login/" +
                "?gw_id=58b4bbe5d533&gw_sn=H1U50YX004340&gw_address=192.168.110.1" +
                "&gw_port=2060&ip=$ip&mac=$mac&slot_num=8&nasip=192.168.1.225" +
                "&ssid=VLAN233&ustate=0&mac_req=1&url=http%3A%2F%2F192.168.0.1%2F" +
                "&chap_id=%5C025&chap_challenge=%5C236%5C107%5C316%5C175%5C350%5C072%5C314%5C321%5C224%5C254%5C051%5C267%5C127%5C203%5C001%5C032"

        try {
            // Step 1 — follow redirects to get the HTML page
            val req1 = Request.Builder()
                .url(step1Url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val resp1 = clientFollowRedirects.newCall(req1).execute()
            if (resp1.code != 200) {
                return@withContext Result.failure(Exception("Step1 failed: HTTP ${resp1.code}"))
            }

            val body = resp1.body?.string() ?: ""
            log("[*] Step1 body length: ${body.length}")

            // Extract self.location.href = '...' or "..."
            val pattern = Pattern.compile("""self\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            val matcher = pattern.matcher(body)
            if (!matcher.find()) {
                log("[!] No JS redirect found in step1 body")
                return@withContext Result.failure(Exception("No redirect JS found in portal page"))
            }

            val step2Path = matcher.group(1) ?: ""
            val step2Url = resolveUrl("https://portal-as.ruijienetworks.com", step2Path)
            log("[*] Step2 URL: $step2Url")

            // Step 2 — do NOT follow redirects; grab sessionId from Location header
            val req2 = Request.Builder()
                .url(step2Url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val resp2 = client.newCall(req2).execute()
            if (resp2.code != 302) {
                log("[!] Step2 not a redirect (status ${resp2.code})")
                return@withContext Result.failure(Exception("Step2 expected 302, got ${resp2.code}"))
            }

            val location = resp2.header("Location") ?: ""
            val params = parseQueryParams(URI(location).query ?: "")
            val sid = params["sessionId"]

            if (sid.isNullOrBlank()) {
                return@withContext Result.failure(Exception("sessionId not found in redirect"))
            }

            sessionId = sid
            log("[+] Session ID: ${sid.take(16)}...")
            Result.success(sid)
        } catch (e: Exception) {
            log("[!] Session fetch error: ${e.message}")
            Result.failure(Exception("Session error: ${e.message}"))
        }
    }

    // ─── Step 3: CAPTCHA ─────────────────────────────────────────────────────

    suspend fun fetchCaptchaImage(): Result<ByteArray?> = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext Result.failure(Exception("No session ID"))
        val ts = System.currentTimeMillis()
        val url = "https://portal-as.ruijienetworks.com/api/auth/captcha/image?sessionId=$sid&_t=$ts"
        log("[*] Checking CAPTCHA endpoint...")

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val resp = client.newCall(req).execute()
            return@withContext when (resp.code) {
                404 -> {
                    log("[*] CAPTCHA not required (404).")
                    Result.success(null)
                }
                200 -> {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        log("[+] CAPTCHA image received (${bytes.size} bytes)")
                        Result.success(bytes)
                    } else {
                        log("[*] CAPTCHA endpoint returned empty body, skipping.")
                        Result.success(null)
                    }
                }
                else -> {
                    log("[*] CAPTCHA endpoint returned ${resp.code}, skipping.")
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            log("[*] CAPTCHA check error: ${e.message}, skipping.")
            Result.success(null)
        }
    }

    suspend fun verifyCaptcha(authCode: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext Result.failure(Exception("No session ID"))
        log("[*] Verifying CAPTCHA code...")

        val payload = gson.toJson(mapOf("sessionId" to sid, "authCode" to authCode))
        val body = payload.toRequestBody(JSON)

        val req = Request.Builder()
            .url("https://portal-as.ruijienetworks.com/api/auth/captcha/verify")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("Content-Type", "application/json")
            .build()

        try {
            val resp = clientFollowRedirects.newCall(req).execute()
            if (resp.code == 200) {
                val text = resp.body?.string() ?: ""
                val result = gson.fromJson(text, Map::class.java)
                val success = result["success"] as? Boolean ?: false
                return@withContext if (success) {
                    log("[+] CAPTCHA verified successfully")
                    Result.success(true)
                } else {
                    log("[!] Wrong CAPTCHA code")
                    Result.success(false)
                }
            }
            log("[!] CAPTCHA verify HTTP ${resp.code}")
            Result.failure(Exception("CAPTCHA verify failed: HTTP ${resp.code}"))
        } catch (e: Exception) {
            log("[!] CAPTCHA verify error: ${e.message}")
            Result.failure(Exception("CAPTCHA verify error: ${e.message}"))
        }
    }

    // ─── Step 4: Submit Voucher ───────────────────────────────────────────────

    suspend fun submitVoucher(voucher: String, captchaCode: String?): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val sid = sessionId ?: return@withContext Result.failure(Exception("No session ID"))
            log("[*] Submitting voucher: $voucher")

            val apiUrl = String(
                Base64.decode(
                    "aHR0cHM6Ly9wb3J0YWwtYXMucnVpamllbmV0d29ya3MuY29tL2FwaS9hdXRoL3ZvdWNoZXIvP2xhbmc9ZW5fVVM=",
                    Base64.DEFAULT
                )
            ).trim()

            val payloadMap = mutableMapOf<String, Any>(
                "accessCode" to voucher,
                "sessionId" to sid,
                "apiVersion" to 1
            )
            if (!captchaCode.isNullOrBlank()) {
                payloadMap["captcha"] = captchaCode
            }

            val bodyStr = gson.toJson(payloadMap)
            val reqBody = bodyStr.toRequestBody(JSON)

            val req = Request.Builder()
                .url(apiUrl)
                .post(reqBody)
                .header("authority", "portal-as.ruijienetworks.com")
                .header("accept", "*/*")
                .header("content-type", "application/json")
                .header("origin", "https://portal-as.ruijienetworks.com")
                .header(
                    "referer",
                    "https://portal-as.ruijienetworks.com/download/static/maccauth/src/index.html?sessionId=$sid"
                )
                .header("user-agent", "Mozilla/5.0 (Linux; Android 12)")
                .build()

            try {
                val resp = clientFollowRedirects.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                log("[DEBUG] Voucher response: ${text.take(300)}")

                return@withContext if ("logonUrl" in text) {
                    log("[+] Voucher '$voucher' accepted!")
                    Result.success(true)
                } else {
                    log("[!] Voucher rejected. Response: ${text.take(200)}")
                    Result.success(false)
                }
            } catch (e: Exception) {
                log("[!] Voucher error: ${e.message}")
                Result.failure(Exception("Voucher error: ${e.message}"))
            }
        }

    // ─── Step 5: Activate Internet ────────────────────────────────────────────

    suspend fun activateInternet(): Result<Boolean> = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext Result.failure(Exception("No session ID"))
        val ip = gatewayIp ?: return@withContext Result.failure(Exception("No gateway IP"))

        log("[*] Activating internet access...")

        val url = "http://$ip:2060/wifidog/auth?token=$sid&phoneNumber=12345678901"

        val req = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            val resp = clientFollowRedirects.newCall(req).execute()
            return@withContext if (resp.code == 200) {
                log("[+] Internet activated successfully!")
                Result.success(true)
            } else {
                log("[!] Activation failed (HTTP ${resp.code})")
                Result.success(false)
            }
        } catch (e: Exception) {
            log("[!] Activation error: ${e.message}")
            Result.failure(Exception("Activation error: ${e.message}"))
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate { pair ->
            val parts = pair.split("=", limit = 2)
            val key = parts[0]
            val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }

    private fun resolveUrl(base: String, path: String): String {
        return if (path.startsWith("http")) path
        else if (path.startsWith("/")) {
            val uri = URI(base)
            "${uri.scheme}://${uri.host}$path"
        } else {
            "$base/$path"
        }
    }
}
