package blbl.cat3399.core.net

import android.util.Base64
import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

object WebCookieMaintainer {
    private const val TAG = "WebCookieMaintainer"

    private const val BILI_TICKET_KEY_ID = "ec02"
    private const val BILI_TICKET_HMAC_KEY = "XgwSnGZ1p"

    private const val REFRESH_SOURCE = "main_web"

    private val refreshCsrfRegex = Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")

    // From bilibili-api-docs cookie_refresh.md
    private val correspondPublicKey: PublicKey by lazy {
        val derBase64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
        val keyBytes = Base64.decode(derBase64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    suspend fun ensureHealthyForPlay() {
        ensureWebFingerprintCookies()
        ensureBiliTicket()
        refreshCookieIfNeededOncePerDay()
    }

    suspend fun ensureWebFingerprintCookies() {
        val hasBuvid3 = !BiliClient.cookies.getCookieValue("buvid3").isNullOrBlank()
        val hasBNut = !BiliClient.cookies.getCookieValue("b_nut").isNullOrBlank()
        if (!hasBuvid3 || !hasBNut) {
            runCatching { BiliClient.getBytes("https://www.bilibili.com/") }
                .onFailure { AppLog.w(TAG, "ensureWebFingerprintCookies homepage failed", it) }
        }

        val hasBuvid4 = !BiliClient.cookies.getCookieValue("buvid4").isNullOrBlank()
        if (!hasBuvid4) {
            runCatching {
                val json = BiliClient.getJson("https://api.bilibili.com/x/frontend/finger/spi")
                val data = json.optJSONObject("data") ?: JSONObject()
                val b3 = data.optString("b_3", "").trim()
                val b4 = data.optString("b_4", "").trim()
                val expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                val out = ArrayList<Cookie>(2)
                if (b3.isNotBlank() && BiliClient.cookies.getCookieValue("buvid3").isNullOrBlank()) {
                    out.add(buildCookie(name = "buvid3", value = b3, expiresAt = expiresAt))
                }
                if (b4.isNotBlank()) {
                    out.add(buildCookie(name = "buvid4", value = b4, expiresAt = expiresAt))
                }
                if (out.isNotEmpty()) BiliClient.cookies.upsertAll(out)
            }.onFailure {
                AppLog.w(TAG, "ensureWebFingerprintCookies finger/spi failed", it)
            }
        }
    }

    suspend fun ensureBiliTicket() {
        val nowMs = System.currentTimeMillis()
        val epochDay = nowMs / 86_400_000L
        val existing = BiliClient.cookies.getCookie("bili_ticket")
        if (existing != null && existing.expiresAt - nowMs > 6 * 60 * 60 * 1000) return
        if (BiliClient.prefs.biliTicketCheckedEpochDay == epochDay) return
        BiliClient.prefs.biliTicketCheckedEpochDay = epochDay

        runCatching {
            val ts = (nowMs / 1000).toString()
            val hexsign = hmacSha256Hex(key = BILI_TICKET_HMAC_KEY, message = "ts$ts")
            val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty()
            val url =
                BiliClient.withQuery(
                    "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket",
                    buildMap {
                        put("key_id", BILI_TICKET_KEY_ID)
                        put("hexsign", hexsign)
                        put("context[ts]", ts)
                        if (csrf.isNotBlank()) put("csrf", csrf)
                    },
                )
            val json =
                BiliClient.requestString(
                    url,
                    method = "POST",
                    body = ByteArray(0).toRequestBody(null),
                ).let { raw -> withContext(Dispatchers.Default) { JSONObject(raw) } }
            val data = json.optJSONObject("data") ?: JSONObject()
            val ticket = data.optString("ticket", "").trim()
            val createdAt = data.optLong("created_at", 0L)
            val ttl = data.optLong("ttl", 0L)
            if (ticket.isBlank() || createdAt <= 0L || ttl <= 0L) return@runCatching

            val expiresSec = createdAt + ttl
            val expiresAt = expiresSec * 1000L
            BiliClient.cookies.upsertAll(
                listOf(
                    buildCookie(name = "bili_ticket", value = ticket, expiresAt = expiresAt),
                    buildCookie(name = "bili_ticket_expires", value = expiresSec.toString(), expiresAt = expiresAt),
                ),
            )
            AppLog.i(TAG, "bili_ticket updated")
        }.onFailure {
            AppLog.w(TAG, "ensureBiliTicket failed", it)
        }
    }

    suspend fun refreshCookieIfNeededOncePerDay() {
        if (!BiliClient.cookies.hasSessData()) return
        val refreshToken = BiliClient.prefs.webRefreshToken?.takeIf { it.isNotBlank() } ?: return

        val nowMs = System.currentTimeMillis()
        val epochDay = nowMs / 86_400_000L
        if (BiliClient.prefs.webCookieRefreshCheckedEpochDay == epochDay) return
        BiliClient.prefs.webCookieRefreshCheckedEpochDay = epochDay

        val biliJct = BiliClient.cookies.getCookieValue("bili_jct")?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val infoUrl =
                BiliClient.withQuery(
                    "https://passport.bilibili.com/x/passport-login/web/cookie/info",
                    mapOf("csrf" to biliJct),
                )
            val info = BiliClient.getJson(infoUrl)
            val data = info.optJSONObject("data") ?: JSONObject()
            val shouldRefresh = data.optBoolean("refresh", false)
            if (!shouldRefresh) return@runCatching

            val ts = data.optLong("timestamp", nowMs).takeIf { it > 0 } ?: nowMs
            val correspondPath = withContext(Dispatchers.Default) { getCorrespondPath(ts) }
            val html = BiliClient.requestString("https://www.bilibili.com/correspond/1/$correspondPath")
            val refreshCsrf = refreshCsrfRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (refreshCsrf.isBlank()) error("refresh_csrf not found")

            val refreshResult =
                BiliClient.postFormJson(
                    "https://passport.bilibili.com/x/passport-login/web/cookie/refresh",
                    form =
                        mapOf(
                            "csrf" to biliJct,
                            "refresh_csrf" to refreshCsrf,
                            "source" to REFRESH_SOURCE,
                            "refresh_token" to refreshToken,
                        ),
                )
            val refreshData = refreshResult.optJSONObject("data") ?: JSONObject()
            val newRefreshToken = refreshData.optString("refresh_token", "").trim()
            if (newRefreshToken.isNotBlank()) BiliClient.prefs.webRefreshToken = newRefreshToken

            val newBiliJct = BiliClient.cookies.getCookieValue("bili_jct")?.takeIf { it.isNotBlank() } ?: biliJct
            runCatching {
                BiliClient.postFormJson(
                    "https://passport.bilibili.com/x/passport-login/web/confirm/refresh",
                    form =
                        mapOf(
                            "csrf" to newBiliJct,
                            "refresh_token" to refreshToken,
                        ),
                )
            }

            AppLog.i(TAG, "web cookie refreshed")
        }.onFailure {
            AppLog.w(TAG, "refreshCookieIfNeededOncePerDay failed", it)
        }
    }

    private fun buildCookie(name: String, value: String, expiresAt: Long): Cookie {
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain("bilibili.com")
            .path("/")
            .expiresAt(expiresAt)
            .secure()
            .build()
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun getCorrespondPath(timestampMs: Long): String {
        val plaintext = "refresh_$timestampMs"
        val input = plaintext.toByteArray(StandardCharsets.UTF_8)
        val cipher =
            runCatching { Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding") }
                .getOrElse { Cipher.getInstance("RSA/ECB/OAEPPadding") }
        cipher.init(
            Cipher.ENCRYPT_MODE,
            correspondPublicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        val encrypted = cipher.doFinal(input)
        val sb = StringBuilder(encrypted.size * 2)
        for (b in encrypted) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
