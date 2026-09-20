package io.relayfs.android.core.provider.pcloud

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.provider.pcloud.auth.OAuthPkceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OAuthPkceHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testCodeVerifierGeneration() {
        val verifierDefault = OAuthPkceHelper.generateCodeVerifier()
        assertEquals(64, verifierDefault.length)
        assertTrue(verifierDefault.matches(Regex("^[A-Za-z0-9._~-]+$")))

        val verifierCustom = OAuthPkceHelper.generateCodeVerifier(128)
        assertEquals(128, verifierCustom.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCodeVerifierTooShortThrows() {
        OAuthPkceHelper.generateCodeVerifier(42)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCodeVerifierTooLongThrows() {
        OAuthPkceHelper.generateCodeVerifier(129)
    }

    @Test
    fun testRfc7636AppendixBTestVector() {
        // Test vector from RFC 7636 Appendix B:
        val codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val computedChallenge = OAuthPkceHelper.generateCodeChallenge(codeVerifier)
        assertEquals(expectedChallenge, computedChallenge)
    }

    @Test
    fun testBuildAuthorizeUrlDefaultRedirect() {
        val clientId = "prod_client_456"
        val url = OAuthPkceHelper.buildAuthorizeUrl(clientId = clientId)

        assertTrue(url.startsWith("https://my.pcloud.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=prod_client_456"))
        assertTrue(url.contains("response_type=token"))
        assertFalse(url.contains("code_challenge="))
        assertTrue(url.contains("redirect_uri=pcloud-oauth%3A%2F%2Fio.relayfs.android"))
    }

    @Test
    fun testBuildAuthorizeUrl() {
        val clientId = "my_client_id_123"
        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val redirectUri = "pcloud-oauth://io.relayfs.android"

        val url = OAuthPkceHelper.buildAuthorizeUrl(clientId, challenge, redirectUri)

        assertTrue(url.startsWith("https://my.pcloud.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=my_client_id_123"))
        assertTrue(url.contains("code_challenge=$challenge"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri=pcloud-oauth%3A%2F%2Fio.relayfs.android"))
    }

    @Test
    fun testBuildAuthorizeUrlIncludesState() {
        val url = OAuthPkceHelper.buildAuthorizeUrl(
            clientId = "client",
            state = "state/value+with spaces"
        )

        assertTrue(url.contains("state=state%2Fvalue%2Bwith+spaces"))
    }

    @Test
    fun testOAuthStateIsSingleUseAndRejectsMismatch() {
        OAuthPkceHelper.savePendingState(context, "expected-state")

        assertFalse(OAuthPkceHelper.validateAndConsumeState(context, "wrong-state"))
        assertTrue(OAuthPkceHelper.validateAndConsumeState(context, "expected-state"))
        assertFalse(OAuthPkceHelper.validateAndConsumeState(context, "expected-state"))
    }

    @Test
    fun testExpectedRedirectUriValidation() {
        assertTrue(
            OAuthPkceHelper.isExpectedRedirectUri(
                Uri.parse("pcloud-oauth://io.relayfs.android?code=abc")
            )
        )
        assertFalse(OAuthPkceHelper.isExpectedRedirectUri(Uri.parse("relayfs://oauth?code=abc")))
        assertFalse(
            OAuthPkceHelper.isExpectedRedirectUri(
                Uri.parse("pcloud-oauth://malicious.example?code=abc")
            )
        )
    }

    @Test
    fun testBuildAuthorizeUrlTokenFlow() {
        val clientId = "prod_client_456"
        val redirectUri = "pcloud-oauth://io.relayfs.android"

        val url = OAuthPkceHelper.buildAuthorizeUrl(clientId = clientId, redirectUri = redirectUri)

        assertTrue(url.startsWith("https://my.pcloud.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=prod_client_456"))
        assertTrue(url.contains("response_type=token"))
        assertFalse(url.contains("code_challenge="))
        assertTrue(url.contains("redirect_uri=pcloud-oauth%3A%2F%2Fio.relayfs.android"))
    }

    @Test
    fun testParseCallbackUriTokenFlowFragment() {
        val uri = Uri.parse("pcloud-oauth://io.relayfs.android#access_token=sec_token_999&token_type=bearer&locationid=2&hostname=eapi.pcloud.com")
        val params = OAuthPkceHelper.parseCallbackUri(uri)

        assertEquals("sec_token_999", params["access_token"])
        assertEquals("bearer", params["token_type"])
        assertEquals("2", params["locationid"])
        assertEquals("eapi.pcloud.com", params["hostname"])
    }

    @Test
    fun testParseCallbackUriCodeFlowQuery() {
        val uri = Uri.parse("pcloud-oauth://io.relayfs.android?code=auth_code_123&locationid=1")
        val params = OAuthPkceHelper.parseCallbackUri(uri)

        assertEquals("auth_code_123", params["code"])
        assertEquals("1", params["locationid"])
    }
}
