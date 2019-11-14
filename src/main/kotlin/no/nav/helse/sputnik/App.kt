package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.install
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
val objectMapper: ObjectMapper = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())
val log = LoggerFactory.getLogger("sputnik")

fun main() = runBlocking {
    val credentials = readCredentials()
    val environment = setUpEnvironment()

    launchApplication(environment, credentials)
}

fun launchApplication(
    environment: Environment,
    credentials: Credentials
) {
    val applicationContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    val exceptionHandler = CoroutineExceptionHandler { context, e ->
        log.error("Feil i lytter", e)
        context.cancel(CancellationException("Feil i lytter", e))
    }
    runBlocking(exceptionHandler + applicationContext) {
        val server = embeddedServer(Netty, 8080) {
            install(MicrometerMetrics) {
                registry = meterRegistry
            }

            routing {
                registerHealthApi({ true }, { true }, meterRegistry)
            }
        }.start(wait = false)

        launchListeners(environment, credentials)

        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop(10, 10, TimeUnit.SECONDS)
            applicationContext.close()
        })
    }
}

fun CoroutineScope.launchListeners(
    environment: Environment,
    credentials: Credentials,
    baseConfig: Properties = loadBaseConfig(environment, credentials)
): Job {
    val løsningService = LøsningService()
    val behovProducer = KafkaProducer<String, JsonNode>(baseConfig.toProducerConfig())

    return listen<String, JsonNode>(environment.spleisBehovtopic, baseConfig.toConsumerConfig()) {
        val behov = it.value()
        if (behov["@behov"].asText() == "Ytelsesbehov" && behov["@løsning"].isNullOrMissing()) {
            val løsning = løsningService.løsBehov(behov)
            behovProducer.send(ProducerRecord(environment.spleisBehovtopic, it.key(), løsning))
        }
    }
}

fun JsonNode?.isNullOrMissing() = this == null || isNull
