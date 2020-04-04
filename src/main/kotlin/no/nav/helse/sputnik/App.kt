package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.rapids_rivers.RapidsConnection

internal val objectMapper: ObjectMapper = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())

fun main() {
    val serviceUser = readServiceUserCredentials()
    val rapidsConnection = launchApplication(System.getenv(), serviceUser)
    rapidsConnection.start()
}

fun launchApplication(
    environment: Map<String, String>,
    serviceUser: ServiceUser
): RapidsConnection {

    val stsRestClient = StsRestClient(environment.getValue("STS_BASE_URL"), serviceUser)
    val fpsakRestClient = FpsakRestClient(
        baseUrl = environment.getValue("FPSAK_BASE_URL"),
        httpClient = simpleHttpClient(),
        stsRestClient = stsRestClient
    )

    return RapidApplication.create(environment).apply {
        Foreldrepenger(this, fpsakRestClient)
    }
}

private fun simpleHttpClient(serializer: JacksonSerializer? = JacksonSerializer()) = HttpClient() {
    install(JsonFeature) {
        this.serializer = serializer
    }
}
