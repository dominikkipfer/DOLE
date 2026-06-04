package dole.wallet

import dole.Constants
import dole.card.SmartCard
import dole.core.CoreWrapper
import dole.data.AccountStorage
import dole.data.models.GenesisTransaction
import dole.data.models.SendTransaction
import dole.data.models.Transaction
import dole.utils.ProtocolSerializer

class WalletService(
    private val card: SmartCard,
    private val pin: String,
    private val storage: AccountStorage
) {
    val currentUserId: String
    val isMinter: Boolean

    init {
        if (!card.isConnected) card.connect()
        val pinBytes = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())
        if (!card.verifyPin(pinBytes)) throw Exception("Invalid PIN for SmartCard")

        val pubKey = card.publicKey ?: throw Exception("Missing Public Key")
        currentUserId = CoreWrapper.getPersonIdAsHex(pubKey)

        isMinter = try {
            card.isMinter
        } catch (_: Exception) {
            false
        }
    }

    fun syncIncomingWithCard(rustHistory: List<Transaction>) {
        if (!card.isConnected) card.connect()
        val pinBytes = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())
        card.verifyPin(pinBytes)

        val lastReceived = storage.getLastReceived(currentUserId).toMutableMap()
        var updated = false

        val incomingTxs = rustHistory.filterIsInstance<SendTransaction>().filter { it.target == currentUserId }

        for (tx in incomingTxs) {
            val cardKnowsGoc = lastReceived[tx.author] ?: 0L

            if (tx.goc > cardKnowsGoc) {
                try {
                    val senderGenesis = rustHistory.filterIsInstance<GenesisTransaction>().find { it.author == tx.author }
                    val senderPubKey = CoreWrapper.hexToBytes(senderGenesis?.publicKey ?: "")

                    val peerCertificate = senderGenesis?.attachmentCertificate
                        ?: throw IllegalStateException("No certificate found for sender: ${tx.author}")

                    ensurePeerRegistered(senderPubKey, peerCertificate)

                    val signatureBytes = CoreWrapper.hexToBytes(tx.signature)

                    val senderIdBytes = CoreWrapper.hexToBytes(CoreWrapper.getPersonIdAsHex(senderPubKey))
                    val myIdBytes = CoreWrapper.hexToBytes(currentUserId)

                    val logPayload = ProtocolSerializer.buildLogPayload(
                        tx.seq, Constants.OP_SEND, senderIdBytes, myIdBytes, tx.goc
                    )

                    val payload = ProtocolSerializer.buildReceivePayload(
                        senderPubKey,
                        signatureBytes,
                        logPayload
                    )

                    card.processReceive(payload)

                    lastReceived[tx.author] = tx.goc
                    updated = true
                } catch (e: Exception) {
                    println("Sync failed ${tx.id}: ${e.message}")
                }
            }
        }

        if (updated) {
            storage.saveLastReceived(currentUserId, lastReceived)
        }
    }

    private fun ensurePeerRegistered(peerPubKey: ByteArray, peerCertificate: ByteArray) {
        try {
            val payload = ProtocolSerializer.buildAddPeerPayload(peerCertificate, peerPubKey)
            card.addPeer(payload)
        } catch (e: Exception) {
            println("Card rejected Peer Registration: ${e.message}")
        }
    }

    fun mint(amount: Long) {
        if (!card.isConnected) card.connect()
        val pinBytes = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())
        card.verifyPin(pinBytes)

        val payload = ProtocolSerializer.buildMintBurnPayload(amount)
        val response = card.processMint(payload)

        val sigHex = CoreWrapper.bytesToHex(ProtocolSerializer.parseSignatureFromResponse(response))
        CoreWrapper.mint(amount, sigHex)
    }

    fun burn(amount: Long) {
        if (!card.isConnected) card.connect()
        val pinBytes = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())
        card.verifyPin(pinBytes)

        val payload = ProtocolSerializer.buildMintBurnPayload(amount)
        val response = card.processBurn(payload)

        val sigHex = CoreWrapper.bytesToHex(ProtocolSerializer.parseSignatureFromResponse(response))
        CoreWrapper.burn(amount, sigHex)
    }

    fun send(targetId: String, amount: Long, rustHistory: List<Transaction>) {
        require(amount > 0) { "Amount must be > 0" }

        if (!card.isConnected) card.connect()
        val pinBytes = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())
        card.verifyPin(pinBytes)

        val targetGenesis = rustHistory.filterIsInstance<GenesisTransaction>().find { it.author == targetId }
        val targetPubKeyHex = targetGenesis?.publicKey
            ?: throw IllegalStateException("Cannot send: Target peer certificate not discovered on ledger yet.")
        val targetPubKey = CoreWrapper.hexToBytes(targetPubKeyHex)

        val targetCertificate = targetGenesis?.attachmentCertificate
            ?: throw IllegalStateException("Cannot send: Target peer certificate not discovered on ledger yet.")

        ensurePeerRegistered(targetPubKey, targetCertificate)

        val payload = ProtocolSerializer.buildSendPayload(targetPubKey, amount)
        val response = card.processSend(payload)

        val sigHex = CoreWrapper.bytesToHex(ProtocolSerializer.parseSignatureFromResponse(response))
        CoreWrapper.send(targetId, amount, sigHex)
    }

    fun close() {
        try {
            card.disconnect()
        } catch (_: Exception) {}
    }
}
