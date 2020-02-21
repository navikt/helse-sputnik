package no.nav.helse.sputnik

import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.nav.helse.rapids_rivers.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class LøsningService(rapidsConnection: RapidsConnection, private val fpsakRestClient: FpsakRestClient) :
    River.PacketListener {

    private companion object {
        private val log = LoggerFactory.getLogger(LøsningService::class.java)
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
    }

    init {
        River(rapidsConnection).apply {
            validate { it.requireAll("@behov", listOf("Foreldrepenger")) }
            validate { it.forbid("@løsning") }
            validate { it.requireKey("@id") }
            validate { it.requireKey("aktørId") }
            validate { it.requireKey("vedtaksperiodeId") }
            validate { it.requireKey("@opprettet") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        if (packet["@opprettet"].asLocalDateTime() < LocalDateTime.of(2020, 2, 21, 14, 0, 0)) return

        sikkerlogg.info("mottok melding: ${packet.toJson()}")
        try {
            runBlocking { hentYtelser(packet["aktørId"].asText()) }.also {
                packet["@løsning"] = mapOf(
                    "Foreldrepenger" to it
                )
            }

            log.info(
                "løser behov={} for {}",
                keyValue("id", packet["@id"].asText()),
                keyValue("vedtaksperiodeId", packet["vedtaksperiodeId"].asText())
            )
            sikkerlogg.info(
                "løser behov={} for {} = ${packet.toJson()}",
                keyValue("id", packet["@id"].asText()),
                keyValue("vedtaksperiodeId", packet["vedtaksperiodeId"].asText())
            )

            context.send(packet.toJson())
        } catch (err: Exception) {
            log.error(
                "feil ved henting av foreldrepenger-data: ${err.message} for {}",
                keyValue("vedtaksperiodeId", packet["vedtaksperiodeId"].asText()),
                err
            )
            sikkerlogg.error(
                "feil ved henting av foreldrepenger-data: ${err.message} for {}",
                keyValue("vedtaksperiodeId", packet["vedtaksperiodeId"].asText()),
                err
            )
        }
    }

    override fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {}

    private suspend fun hentYtelser(aktørId: String): Foreldrepengerløsning {
        val foreldrepengeytelse = fpsakRestClient.hentGjeldendeForeldrepengeytelse(aktørId)
        val svangerskapsytelse = fpsakRestClient.hentGjeldendeSvangerskapsytelse(aktørId)
        return Foreldrepengerløsning(foreldrepengeytelse, svangerskapsytelse)
    }

    class Foreldrepengerløsning(
        @JvmField val Foreldrepengeytelse: Ytelse? = null,
        @JvmField val Svangerskapsytelse: Ytelse? = null
    )
}

