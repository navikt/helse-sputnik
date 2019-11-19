package no.nav.helse.sputnik

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.fullPath
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

internal class FpsakRestClientTest {

    @Test
    fun `henter ytelser fra foreldrepenger`() {
        val ytelser = fpsakRestClient.hentForeldrepengeytelser("aktør")
        assertEquals(2, ytelser.size)
        assertEquals(LocalDate.of(2019, 5, 13), ytelser.last().fom)
        assertEquals(LocalDate.of(2020, 1, 30), ytelser.last().tom)
    }

    @Test
    fun `gjør tre kall om paginering settes`() {
        mockResponseGenerator.apply {
            every { stub() }.returnsMany(
                baseResponse(true),
                baseResponse(true),
                baseResponse(false)
            )
        }

        FpsakRestClient(baseUrl, mockClient, mockStsClient).hentForeldrepengeytelser("aktør")
        verify(exactly = 3) { mockResponseGenerator.stub() }
    }

    private val baseUrl = "https://faktiskUrl"
    private val mockResponseGenerator = mockk<ResponseGenerator>(relaxed = true).apply {
        every { stub() }.returns(baseResponse(false))
    }
    private val mockStsClient = mockk<StsRestClient>().apply {
        every { token() }.returns("token")
    }

    private val mockClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                when {
                    (request.url.fullPath).contains("/api/feed/vedtak/foreldrepenger") -> {
                        respond(
                            mockResponseGenerator.stub()
                        )
                    }
                    else -> error("Endepunktet finnes ikke ${request.url.fullPath}")
                }
            }

        }
    }

    private val fpsakRestClient = FpsakRestClient(baseUrl, mockClient, mockStsClient)
}

private class ResponseGenerator() {
    fun stub() = baseResponse()
}

private fun baseResponse(flereElementer: Boolean? = false) = """
{
    "tittel": "ForeldrepengerVedtak_v1",
    "inneholderFlereElementer": $flereElementer,
    "elementer": [
        {
            "type": "ForeldrepengerInnvilget_v1",
            "sekvensId": 23478,
            "innhold": {
                "aktoerId": "1000035271924",
                "foersteStoenadsdag": "2019-05-13",
                "sisteStoenadsdag": "2020-02-03",
                "gsakId": "138593877"
            },
            "metadata": {
                "opprettetDato": "2019-05-15T17:01:40.976+02:00"
            }
        },
        {
            "type": "ForeldrepengerEndret_v1",
            "sekvensId": 26882,
            "innhold": {
                "aktoerId": "1000035271924",
                "foersteStoenadsdag": "2019-05-13",
                "sisteStoenadsdag": "2020-01-30",
                "gsakId": "138593877"
            },
            "metadata": {
                "opprettetDato": "2019-06-03T07:15:28.9+02:00"
            }
        }
    ]
}
"""
