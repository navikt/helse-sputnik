package no.nav.helse.sputnik

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.common.KafkaEnvironment
import no.nav.helse.rapids_rivers.RapidsConnection
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest {

    private val testTopic = "helse-rapid-v1"
    private val embeddedKafkaEnvironment = KafkaEnvironment(
        autoStart = false,
        noOfBrokers = 1,
        topicInfos = listOf(KafkaEnvironment.TopicInfo(name = testTopic, partitions = 1)),
        withSchemaRegistry = false,
        withSecurity = false
    )

    private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
    private lateinit var client: WireMock

    private lateinit var fpsakUrl: String
    private lateinit var stsUrl: String
    private val fpsakPath = "/fpsak"
    private val stsPath = "/sts"

    private lateinit var appUrl: String
    private val serviceUser = ServiceUser("user", "password")

    private lateinit var rapidsConnection: RapidsConnection
    private lateinit var behovProducer: Producer<String, String>
    private lateinit var behovConsumer: Consumer<String, String>

    @BeforeAll
    fun setup() {
        embeddedKafkaEnvironment.start()

        wireMockServer.start()
        fpsakUrl = wireMockServer.baseUrl() + fpsakPath
        stsUrl = wireMockServer.baseUrl() + stsPath

        client = create().port(wireMockServer.port()).build().apply {
            configureFor(this)
        }

        stubFor(
            get(urlPathEqualTo("$stsPath/rest/v1/sts/token"))
                .willReturn(okJson("""{"access_token":"token", "expires_in":3600, "token_type":"yes"}"""))
        )
        stubFor(
            get(urlPathEqualTo("$fpsakPath/fpsak/api/vedtak/gjeldendevedtak-foreldrepenger"))
                .willReturn(okJson("[]"))
        )
        stubFor(
            get(urlPathEqualTo("$fpsakPath/fpsak/api/vedtak/gjeldendevedtak-svangerskapspenger"))
                .willReturn(okJson("[]"))
        )

        behovProducer = KafkaProducer<String, String>(Properties().apply {
            this[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = embeddedKafkaEnvironment.brokersURL
            this[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
            this[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
            this[ProducerConfig.LINGER_MS_CONFIG] = "0"
        })
        behovConsumer = KafkaConsumer<String, String>(Properties().apply {
            this[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = embeddedKafkaEnvironment.brokersURL
            this[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
            this[ConsumerConfig.GROUP_ID_CONFIG] = "test-consumer"
            this[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
            this[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
            this[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = "1000"
        }).also {
            it.subscribe(listOf(testTopic))
        }

        val randomPort = ServerSocket(0).use { it.localPort }
        appUrl = "http://localhost:$randomPort"

        val sputnikConsumerGroup = "sputnik-test"
        rapidsConnection = launchApplication(mapOf(
            "KAFKA_BOOTSTRAP_SERVERS" to embeddedKafkaEnvironment.brokersURL,
            "KAFKA_RAPID_TOPIC" to testTopic,
            "KAFKA_CONSUMER_GROUP_ID" to sputnikConsumerGroup,
            "FPSAK_BASE_URL" to fpsakUrl,
            "STS_BASE_URL" to stsUrl,
            "HTTP_PORT" to "$randomPort"
        ), serviceUser)

        GlobalScope.launch {
            rapidsConnection.start()
        }

        await("wait until the rapid has started")
            .atMost(5, TimeUnit.SECONDS)
            .until { isOkResponse("/isalive") }

        val adminClient = embeddedKafkaEnvironment.adminClient
        await("wait until the rapid consumer is assigned the topic")
            .atMost(10, TimeUnit.SECONDS)
            .until {
                adminClient?.describeConsumerGroups(listOf(sputnikConsumerGroup))
                    ?.describedGroups()
                    ?.get(sputnikConsumerGroup)
                    ?.get()
                    ?.members()
                    ?.any { it.assignment().topicPartitions().any { it.topic() == testTopic } }
                    ?: false
            }
    }

    private fun isOkResponse(path: String) =
        try {
            (URL("$appUrl$path")
                .openConnection() as HttpURLConnection)
                .responseCode in 200..299
        } catch (err: IOException) {
            false
        }

    @AfterAll
    fun teardown() {
        rapidsConnection.stop()
        wireMockServer.stop()
        embeddedKafkaEnvironment.close()
    }

    @Test
    fun `skal motta behov og produsere løsning`() {
        val behov =
            """{"@id": "behovsid", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123", "vedtaksperiodeId": "1"}"""
        behovProducer.send(ProducerRecord(testTopic, "123", behov))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId("behovsid").size)

            val svar = alleSvar.first()
            assertEquals("123", svar["aktørId"].asText())
            assertTrue(svar["@løsning"].hasNonNull("Foreldrepenger"))
        }
    }

    @Test
    fun `skal kun behandle opprinnelig behov`() {
        val behovAlleredeBesvart =
            """{"@id": "1", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123", "@løsning": { "Sykepengehistorikk": [] }, "vedtaksperiodeId": "1"}"""
        val behovSomTrengerSvar =
            """{"@id": "2", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123", "vedtaksperiodeId": "1"}"""
        behovProducer.send(ProducerRecord(testTopic, "1", behovAlleredeBesvart))
        behovProducer.send(ProducerRecord(testTopic, "2", behovSomTrengerSvar))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId("1").size)
            assertEquals(1, alleSvar.medId("2").size)

            val svar = alleSvar.medId("2").first()
            assertEquals("123", svar["aktørId"].asText())

            assertTrue(svar["@løsning"].hasNonNull("Foreldrepenger"))
            assertEquals("2", svar["@id"].asText())
        }
    }

    @Test
    fun `ignorerer hendelser med ugyldig json`() {
        val behovId = UUID.randomUUID().toString()
        val behovSomTrengerSvar =
            """{"@id": "$behovId", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123", "vedtaksperiodeId": "1"}"""
        behovProducer.send(ProducerRecord(testTopic, UUID.randomUUID().toString(), "THIS IS NOT JSON"))
        behovProducer.send(ProducerRecord(testTopic, behovId, behovSomTrengerSvar))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId(behovId).size)

            val svar = alleSvar.medId(behovId).first()
            assertTrue(svar["@løsning"].hasNonNull("Foreldrepenger"))
        }
    }

    private fun List<JsonNode>.medId(id: String) = filter { it["@id"].asText() == id }

    private fun assertLøsning(duration: Duration, assertion: (List<JsonNode>) -> Unit) =
        mutableListOf<JsonNode>().apply {
            await()
                .atMost(duration)
                .untilAsserted {
                    this.addAll(behovConsumer.poll(Duration.ofMillis(100)).mapNotNull {
                        try {
                            println(it.value())
                            objectMapper.readTree(it.value())
                        } catch (err: JsonParseException) {
                            null
                        }
                    }.filter { it.hasNonNull("@løsning") })

                    assertion(this)
                }
        }
}
