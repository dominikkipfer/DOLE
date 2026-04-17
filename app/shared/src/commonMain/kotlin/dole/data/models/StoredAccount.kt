package dole.data.models

import kotlinx.serialization.Serializable

@Serializable
data class StoredAccount(
    val id: String,
    val name: String,
    val pinHash: String
)