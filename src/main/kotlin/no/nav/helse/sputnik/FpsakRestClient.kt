package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class FpsakRestClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val stsRestClient: StsRestClient
) {
    fun hentForeldrepengeytelser(aktørId: String): List<Foreldrepengeytelse> {
        val ytelser = mutableListOf<Foreldrepengeytelse>()
        runBlocking {
            var sekvens = 0
            val maksAntall = 100
            do {
                val response = httpClient.get<HttpResponse>("$baseUrl/api/feed/vedtak/foreldrepenger") {
                    header("Authorization", "Bearer ${stsRestClient.token()}")
                    accept(ContentType.Application.Json)
                    parameter("aktorId", aktørId)
                    parameter("sistLesteSekvensId", sekvens)
                    parameter("maxAntall", maksAntall)
                }.let { objectMapper.readValue<JsonNode>(it.readText()) }

                (response["elementer"] as ArrayNode)
                    .map { element ->
                        ytelser.add(
                            Foreldrepengeytelse(
                                aktørId = element["innhold"]["aktoerId"].textValue(),
                                fom = element["innhold"]["foersteStoenadsdag"].let { LocalDate.parse(it.textValue()) },
                                tom = element["innhold"]["sisteStoenadsdag"].let { LocalDate.parse(it.textValue()) },
                                inntruffet = element["metadata"]["opprettetDato"].let {
                                    LocalDateTime.parse(
                                        it.textValue(),
                                        DateTimeFormatter.ISO_DATE_TIME
                                    )
                                },
                                type = element["type"].textValue()
                            )
                        )
                    }

                sekvens += maksAntall
            } while (response.get("inneholderFlereElementer").booleanValue())
        }
        return ytelser
    }

    fun hentGjeldendeSvangerskapsytelse(aktørId: String): Svangerskapsytelse? =
        runBlocking {
            httpClient.get<HttpResponse>("$baseUrl/api/vedtak/gjeldendevedtak-svangerskapspenger") {
                header("Authorization", "Bearer ${stsRestClient.token()}")
                accept(ContentType.Application.Json)
                parameter("aktoerId", aktørId)
            }.let {
                objectMapper.readValue<ArrayNode>(it.readText())
            }.map { ytelse ->
                Svangerskapsytelse(
                    aktørId = ytelse["aktør"]["verdi"].textValue(),
                    fom = ytelse["periode"]["fom"].let { LocalDate.parse(it.textValue()) },
                    tom = ytelse["periode"]["tom"].let { LocalDate.parse(it.textValue()) },
                    vedtatt = ytelse["vedtattTidspunkt"].let {
                        LocalDateTime.parse(
                            it.textValue(),
                            DateTimeFormatter.ISO_DATE_TIME
                        )
                    },
                    perioder = (ytelse["anvist"] as ArrayNode).map { periode ->
                        Periode(
                            fom = periode["periode"]["fom"].let { LocalDate.parse(it.textValue()) },
                            tom = periode["periode"]["tom"].let { LocalDate.parse(it.textValue()) }
                        )
                    }
                )
            }
        }.firstOrNull()
}

data class Foreldrepengeytelse(
    val aktørId: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val inntruffet: LocalDateTime,
    val type: String
)

data class Svangerskapsytelse(
    val aktørId: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val vedtatt: LocalDateTime,
    val perioder: List<Periode>
)

data class Periode(
    val fom: LocalDate,
    val tom: LocalDate
)
