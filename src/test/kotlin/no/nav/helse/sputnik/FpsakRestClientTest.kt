package no.nav.helse.sputnik

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.fullPath
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

internal class FpsakRestClientTest {

    @Test
    fun `skal hente foreldrepenge ytelser`() {
        val ytelser = fpsakRestClient.hentForeldrepengeytelser("aktør")
        assertEquals(2, ytelser.size)
        assertEquals(LocalDate.of(2019, 5, 13), ytelser.last().fom)
        assertEquals(LocalDate.of(2020, 1, 30), ytelser.last().tom)
    }

    @Test
    fun `skal gjøre tre kall om paginering settes`() {
        mockResponseGenerator.apply {
            every { foreldrepenger() }.returnsMany(
                baseResponse(true),
                baseResponse(true),
                baseResponse(false)
            )
        }

        FpsakRestClient(baseUrl, mockClient, mockStsClient).hentForeldrepengeytelser("aktør")
        verify(exactly = 3) { mockResponseGenerator.foreldrepenger() }
    }

    @Test
    fun `skal hente svangerskapspenger ytelse`() {
        val ytelse = fpsakRestClient.hentGjeldendeSvangerskapsytelse("aktør")
        val forventetYtelse = Svangerskapsytelse(
            aktørId = "aktør",
            fom = LocalDate.of(2019, 10, 1),
            tom = LocalDate.of(2020, 2, 7),
            vedtatt = LocalDateTime.of(2019, 10, 18, 0, 0, 0),
            perioder = listOf(
                Periode(
                    fom = LocalDate.of(2019, 10, 1),
                    tom = LocalDate.of(2020, 2, 7)
                )
            )
        )

        assertEquals(forventetYtelse, ytelse)
    }

    @Test
    fun `skal returnere null hvis responsen er tom`() {
        mockResponseGenerator.apply {
            every { svangerskapspenger() }.returns("[]")
        }
        val ytelse = fpsakRestClient.hentGjeldendeSvangerskapsytelse("aktør")

        assertNull(ytelse, "skal returnere null hvis bruker ikke har svangerskapspenger")
    }

    private val baseUrl = "https://faktiskUrl"
    private val mockResponseGenerator = mockk<ResponseGenerator>(relaxed = true).apply {
        every { foreldrepenger() }.returns(baseResponse(false))
        every { svangerskapspenger() }.returns(svangerskapspengerResponse())
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
                            mockResponseGenerator.foreldrepenger()
                        )
                    }
                    (request.url.fullPath).contains("/api/vedtak/gjeldendevedtak-svangerskapspenger") -> {
                        respond(
                            mockResponseGenerator.svangerskapspenger()
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
    fun foreldrepenger() = baseResponse()
    fun svangerskapspenger() = svangerskapspengerResponse()

}

private fun svangerskapspengerResponse() = """
[
    {
        "version": "1.0",
        "aktør": {
            "verdi": "aktør"
        },
        "vedtattTidspunkt": "2019-10-18T00:00:00",
        "type": {
            "kode": "SVP",
            "kodeverk": "FAGSAK_YTELSE_TYPE"
        },
        "saksnummer": "140260023",
        "vedtakReferanse": "20e89c46-9956-4e8d-a0fb-174e079f331f",
        "status": {
            "kode": "LOP",
            "kodeverk": "YTELSE_STATUS"
        },
        "fagsystem": {
            "kode": "FPSAK",
            "kodeverk": "FAGSYSTEM"
        },
        "periode": {
            "fom": "2019-10-01",
            "tom": "2020-02-07"
        },
        "anvist": [
            {
                "periode": {
                    "fom": "2019-10-01",
                    "tom": "2020-02-07"
                },
                "beløp": null,
                "dagsats": null,
                "utbetalingsgrad": null
            }
        ]
    }
]
"""

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
