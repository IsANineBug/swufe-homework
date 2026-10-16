package com.swufe.homework.data.api

import android.annotation.SuppressLint

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 服务器用 IP + 自签证书，系统 CA 无法校验。这里只信任「叶子证书公钥 SHA-256」等于内置 pin 的证书，
 * 效果等同 OkHttp CertificatePinner，但能用于自签证书。
 * pin 格式与 deploy 脚本输出一致：`sha256/<base64>`，可逗号分隔多个（换证书时并存）。
 */
object PinnedTrust {
    fun parsePins(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.removePrefix("sha256/") }

    fun spkiSha256(cert: X509Certificate): String =
        java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    @SuppressLint("CustomX509TrustManager")
    class PinTrustManager(private val pins: List<String>, private val hash: (X509Certificate) -> String = ::spkiSha256) :
        X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client auth not supported")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
            leaf.checkValidity()
            if (hash(leaf) !in pins) throw CertificateException("证书公钥与内置 pin 不一致")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    data class Tls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /**
     * 有 pin 时只接受 pin 匹配的证书；主机名用 OkHttp 默认校验（deploy/scripts/gen-cert.sh 生成的证书带 IP SAN）。
     * pin 为空只允许 debug 构建（本地对接正规证书的测试服务器），release 直接抛错，绝不静默退回系统 CA。
     */
    fun build(rawPins: String, allowEmpty: Boolean): Tls {
        val pins = parsePins(rawPins)
        if (pins.isEmpty() && !allowEmpty) throw IllegalStateException("release 构建缺少 SERVER_CERT_PIN，拒绝建立连接")
        val tm: X509TrustManager = if (pins.isEmpty()) {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            f.trustManagers.filterIsInstance<X509TrustManager>().first()
        } else PinTrustManager(pins)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return Tls(ctx.socketFactory, tm)
    }
}
