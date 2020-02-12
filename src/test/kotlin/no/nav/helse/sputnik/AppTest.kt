package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.YearMonth
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest : CoroutineScope {
    override val coroutineContext: CoroutineContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val testTopic = "helse-rapid-v1"
    private val topicInfos = listOf(
        KafkaEnvironment.TopicInfo(testTopic)
    )

    private val embeddedKafkaEnvironment = KafkaEnvironment(
        autoStart = false,
        noOfBrokers = 1,
        topicInfos = topicInfos,
        withSchemaRegistry = false,
        withSecurity = false
    )

    private val serviceUser = ServiceUser("user", "password")
    private val environment = Environment(
        kafkaBootstrapServers = embeddedKafkaEnvironment.brokersURL,
        spleisRapidtopic = testTopic,
        fpsakBaseUrl = "http://fpsakBaseUrl.local"
    )
    private val testKafkaProperties = loadBaseConfig(environment, serviceUser).apply {
        this[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "PLAINTEXT"
        this[SaslConfigs.SASL_MECHANISM] = "PLAIN"
    }

    private lateinit var job: Job

    private val behovProducer = KafkaProducer<String, String>(testKafkaProperties.toProducerConfig()
        .also {
            it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        })
    private val behovConsumer = KafkaConsumer<String, JsonNode?>(testKafkaProperties.toConsumerConfig().also {
        it[ConsumerConfig.GROUP_ID_CONFIG] = "noefornuftigværsåsnill"
    }).also {
        it.subscribe(listOf(testTopic))
    }

    private val mockGenerator = mockk<ResponseGenerator>(relaxed = true).apply {
        every { foreldrepenger() }.returns("[]")
        every { svangerskapspenger() }.returns("[]")
    }
    private val mockHttpClient = fpsakMockClient(mockGenerator)

    private val fpsakRestClient = FpsakRestClient("http://baseUrl.local", mockHttpClient, mockStsRestClient)

    private val løsningService = LøsningService(fpsakRestClient)

    @FlowPreview
    @BeforeAll
    fun setup() {
        embeddedKafkaEnvironment.start()
        job = GlobalScope.launch { launchFlow(environment, serviceUser, løsningService, testKafkaProperties) }
    }

    @Test
    fun `skal motta behov og produsere løsning`() {
        val behov = """{"@id": "behovsid", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123"}"""
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
            """{"@id": "1", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123", "@løsning": { "Sykepengehistorikk": [] }}"""
        val behovSomTrengerSvar = """{"@id": "2", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123"}"""
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
        val behovSomTrengerSvar = """{"@id": "$behovId", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123"}"""
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
        mutableListOf<ConsumerRecord<String, JsonNode?>>().apply {
            await()
                .atMost(duration)
                .untilAsserted {
                    addAll(behovConsumer.poll(Duration.ofMillis(100)).toList())
                    assertion(mapNotNull { it.value() }.filter { it.hasNonNull("@løsning") })
                }
        }

    @AfterAll
    fun tearDown() {
        job.cancel()
        embeddedKafkaEnvironment.close()
    }
}
