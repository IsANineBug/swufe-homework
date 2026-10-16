package com.swufe.homework

import com.swufe.homework.data.api.PinnedTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinTrustManagerTest {
    // 测试用自签证书（EC P-256，有效期 100 年，仅单测使用，无私钥）。
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBhjCCASygAwIBAgIUQSiXFcTE5OAMjFL62xsafr9gxlYwCgYIKoZIzj0EAwIw
        DzENMAsGA1UEAwwEdGVzdDAgFw0yNjEwMTYwNzE3MTBaGA8yMTI2MDkyMjA3MTcx
        MFowDzENMAsGA1UEAwwEdGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABM8K
        AmPSMTDKFwGd4c9jGmJqHwRxcR4TUIjCHd6YETbFYerugPNNJD6OWD5Wy0mbf26H
        5aOtGlwD+Git3f/KpUyjZDBiMB0GA1UdDgQWBBTAfjzAcDHX+V9ic68BmFnm6BSL
        qDAfBgNVHSMEGDAWgBTAfjzAcDHX+V9ic68BmFnm6BSLqDAPBgNVHRMBAf8EBTAD
        AQH/MA8GA1UdEQQIMAaHBH8AAAEwCgYIKoZIzj0EAwIDSAAwRQIgaLhYwHw1A1Ep
        ia03PEh0t7VlNvF2wgvoslcm7/SspXACIQDEm+lLMAMDphyUeqPikOq9/nY8UA/i
        3AWwlQPJOwQl4Q==
        -----END CERTIFICATE-----
    """.trimIndent()

    /** 与 deploy/scripts/gen-cert.sh 同一算法（openssl pkey -pubin -outform der | sha256 | base64）算出的 pin。 */
    private val pin = "ZHdMVuVBmuF6bNz0r3G8DdFtjBl/QuyNkoztuoyMPyU="

    private val cert: X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    @Test fun spkiHashMatchesOpenssl() = assertEquals(pin, PinnedTrust.spkiSha256(cert))

    @Test fun acceptsMatchingPin() {
        PinnedTrust.PinTrustManager(PinnedTrust.parsePins("sha256/$pin")).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test fun acceptsWhenOneOfMultiplePinsMatches() {
        PinnedTrust.PinTrustManager(PinnedTrust.parsePins("sha256/AAAA,sha256/$pin")).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test fun rejectsMismatchedPin() {
        val tm = PinnedTrust.PinTrustManager(listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }
    }

    @Test fun rejectsEmptyChain() {
        val tm = PinnedTrust.PinTrustManager(listOf(pin))
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(), "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(null, "ECDHE_ECDSA") }
    }

    @Test fun rejectsClientAuth() {
        val tm = PinnedTrust.PinTrustManager(listOf(pin))
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert), "RSA") }
    }

    @Test fun releaseWithoutPinFailsClosed() {
        assertThrows(IllegalStateException::class.java) { PinnedTrust.build("", allowEmpty = false) }
        assertThrows(IllegalStateException::class.java) { PinnedTrust.build(" , ", allowEmpty = false) }
    }

    @Test fun pinnedBuildUsesPinTrustManager() {
        val tls = PinnedTrust.build("sha256/$pin", allowEmpty = false)
        org.junit.Assert.assertTrue(tls.trustManager is PinnedTrust.PinTrustManager)
    }
}
