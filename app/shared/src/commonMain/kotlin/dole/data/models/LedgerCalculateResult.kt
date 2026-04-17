package dole.data.models

import kotlinx.serialization.Serializable

@Serializable
data class LedgerCalculateResult(
    val balance: Int,
    val totalMinted: Int,
    val totalBurned: Int,
    val error: String? = null
)