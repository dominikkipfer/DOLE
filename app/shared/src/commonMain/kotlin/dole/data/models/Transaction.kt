package dole.data.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface Transaction {
    val id: String
    val author: String
    val timestamp: Long
    val seq: Long
    val signature: String
}

@Serializable
@SerialName("MINT")
data class MintTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val goc: Long
) : Transaction

@Serializable
@SerialName("BURN")
data class BurnTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val goc: Long
) : Transaction

@Serializable
@SerialName("SEND")
data class SendTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val target: String,
    val goc: Long
) : Transaction

@Serializable
@SerialName("GENESIS")
data class GenesisTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    @SerialName("publicKey") private val declaredPublicKey: String? = null,
    @SerialName("certificate") val certificateHex: String? = null
) : Transaction {
    val publicKey: String get() = declaredPublicKey ?: author
}

val Transaction.amount: Long
    get() = when (this) {
        is MintTransaction -> goc
        is BurnTransaction -> goc
        is SendTransaction -> goc
        is GenesisTransaction -> 0L
    }

val LedgerJson = Json { ignoreUnknownKeys = true }
