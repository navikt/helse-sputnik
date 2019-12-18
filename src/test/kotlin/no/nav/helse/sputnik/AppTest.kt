package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.treeToValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest : CoroutineScope {
    override val coroutineContext: CoroutineContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val testTopic = "privat-helse-sykepenger-behov"
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
        spleisBehovtopic = testTopic,
        fpsakBaseUrl = "http://fpsakBaseUrl.local"
    )
    private val testKafkaProperties = loadBaseConfig(environment, serviceUser).apply {
        this[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "PLAINTEXT"
        this[SaslConfigs.SASL_MECHANISM] = "PLAIN"
    }

    private lateinit var job: Job

    private val behovProducer = KafkaProducer<String, JsonNode>(testKafkaProperties.toProducerConfig())
    private val behovConsumer = KafkaConsumer<String, JsonNode>(testKafkaProperties.toConsumerConfig().also {
        it[ConsumerConfig.GROUP_ID_CONFIG] = "noefornuftigværsåsnill"
    }).also {
        it.subscribe(listOf(testTopic))
    }

    private val mockGenerator = mockk<ResponseGenerator>(relaxed = true).apply {
        every { foreldrepenger() }.returns("[]")
        every { svangerskapspenger() }.returns("[]")
    }
    private val mockHttpClient = fpsakMockClient(mockGenerator)
    private val mockStsRestClient = mockk<StsRestClient>().apply {
        every { token() }.returns("token")
    }

    private val fpsakRestClient = FpsakRestClient("http://baseUrl.local", mockHttpClient, mockStsRestClient)

    private val løsningService = LøsningService(fpsakRestClient)

    @BeforeAll
    fun setup() {
        embeddedKafkaEnvironment.start()
        job = launchListeners(environment, serviceUser, løsningService, testKafkaProperties)
    }

    @Test
    fun `skal motta behov og produsere løsning`() {
        val behov = """{"@id": "behovsid", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123"}"""
        behovProducer.send(ProducerRecord(testTopic, "123", objectMapper.readValue(behov)))

        ventPåLøsning(10) { list ->
            list.firstOrNull { it.value().hasNonNull("@løsning") }
                ?.let { objectMapper.treeToValue<Foreldrepenger>(it.value()["@løsning"]["Foreldrepenger"]) }
        }
    }

    @Test
    fun `skal kun behandle opprinnelig behov`() {
        val behovAlleredeBesvart =
            """{"@id": "1", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123", "@løsning": { "Sykepengehistorikk": [] }}"""
        val behovSomTrengerSvar = """{"@id": "2", "@behov":["Foreldrepenger", "Sykepengehistorikk"], "aktørId":"123"}"""
        behovProducer.send(ProducerRecord(testTopic, "1", objectMapper.readValue(behovAlleredeBesvart)))
        behovProducer.send(ProducerRecord(testTopic, "2", objectMapper.readValue(behovSomTrengerSvar)))

        v2entPåLøsning(200) { list ->
            assertEquals(Foreldrepenger(null, null), list
                .firstOrNull {
                    it.value().hasNonNull("@løsning") && it.value()["@løsning"].hasNonNull("Foreldrepenger")
                }
                ?.let { objectMapper.treeToValue<Foreldrepenger>(it.value()["@løsning"]["Foreldrepenger"]) })
        }
    }

    fun ventPåLøsning(maxDelaySeconds: Long, block: (List<ConsumerRecord<String, JsonNode>>) -> Foreldrepenger?) =
        mutableListOf<ConsumerRecord<String, JsonNode>>().apply {
            await()
                .atMost(maxDelaySeconds, TimeUnit.SECONDS)
                .untilAsserted {
                    addAll(behovConsumer.poll(Duration.ofMillis(100)).toList())
                    assertEquals(Foreldrepenger(null, null), block(this))
                }
        }

    fun v2entPåLøsning(maxDelaySeconds: Long, assertBlock: (List<ConsumerRecord<String, JsonNode>>) -> Unit) =
        mutableListOf<ConsumerRecord<String, JsonNode>>().apply {
            await()
                .atMost(maxDelaySeconds, TimeUnit.SECONDS)
                .untilAsserted {
                    addAll(behovConsumer.poll(Duration.ofMillis(100)).toList())
                    assertBlock(this)
                }
        }


    @AfterAll
    fun tearDown() {
        job.cancel()
        embeddedKafkaEnvironment.close()
    }
}
