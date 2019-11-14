package no.nav.helse.sputnik

import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

const val vaultApplicationPropertiesPath = "/var/run/secrets/nais.io/vault/secrets.json"

fun readCredentials(): Credentials = objectMapper.readValue(File(vaultApplicationPropertiesPath))

fun setUpEnvironment() =
    Environment(
        kafkaBootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS")
            ?: error("Mangler env var KAFKA_BOOTSTRAP_SERVERS")
    )

data class Environment(
    val kafkaBootstrapServers: String,
    val spleisBehovtopic: String = "privat-helse-sykepenger-behov"
)

data class Credentials(
    val serviceUserUsername: String,
    val serviceUserPassword: String
)
