package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.LocalDate

class LøsningService() {
    fun løsBehov(behov: JsonNode): JsonNode = behov.deepCopy<ObjectNode>()
        .set("@løsning", objectMapper.valueToTree(opprettLøsning(behov)))

    private fun opprettLøsning(behov: JsonNode) = Løsning(ytelser = finnYtelserFor(behov["aktørId"].asText()))

    private fun finnYtelserFor(aktørId: String): List<Ytelse> = listOf()
}

private data class Løsning(
    val ytelser: List<Ytelse>
)

private data class Ytelse(
    val type: Ytelsestype,
    val fom: LocalDate,
    val tom: LocalDate
)

private enum class Ytelsestype {

}
