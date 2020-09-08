package no.nav.helse.sputnik

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64

private val serviceuserBasePath = Paths.get("/var/run/secrets/nais.io/service_user")

fun readServiceUserCredentials() = ServiceUser(
    username = Files.readString(serviceuserBasePath.resolve("username")),
    password = Files.readString(serviceuserBasePath.resolve("password"))
)

fun setUpEnvironment() =
    Environment(
        raw = System.getenv(),
        kafkaBootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS")
            ?: error("Mangler env var KAFKA_BOOTSTRAP_SERVERS"),
        fpsakBaseUrl = System.getenv("FPSAK_BASE_URL")
            ?: error("Mangler env var FPSAK_BASE_URL")
    )

data class Environment(
    val raw: Map<String, String>,
    val kafkaBootstrapServers: String,
    val spleisRapidtopic: String = "helse-rapid-v1",
    val stsBaseUrl: String = "http://security-token-service",
    val fpsakBaseUrl: String
)

class ServiceUser(
    val username: String,
    val password: String
) {
    val basicAuth = "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"
}
