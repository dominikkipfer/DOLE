package dole.wallet

import dole.Constants
import dole.card.CardSecureState
import dole.card.SmartCard
import dole.core.CoreWrapper
import dole.data.CardSyncState
import dole.data.models.GenesisTransaction
import dole.data.models.SendTransaction
import dole.data.models.Transaction
import dole.utils.ProtocolSerializer

fun unsyncedIncoming(
    rustHistory: List<Transaction>,
    myId: String,
    lastReceived: Map<String, Long>
): List<SendTransaction> = rustHistory
    .filterIsInstance<SendTransaction>()
    .filter { it.target == myId && it.goc > (lastReceived[it.author] ?: 0L) }

class WalletService(private val card: SmartCard, private val pin: String, private val cardSyncState: CardSyncState) {
    val currentUserId: String
    val isMinter: Boolean

    init {
        if (!card.isConnected) card.connect()
        if (!card.verifyPin(pinBytes())) throw Exception("Invalid PIN for SmartCard")

        val pubKey = card.publicKey ?: throw Exception("Missing Public Key")
        currentUserId = CoreWrapper.getPersonIdAsHex(pubKey)

        isMinter = try {
            card.isMinter
        } catch (_: Exception) {
            false
        }
    }

    private fun pinBytes() = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())

    private fun openSession() {
        if (!card.isConnected) card.connect()
        card.verifyPin(pinBytes())
    }

    fun cardState(): CardSecureState? = try {
        openSession()
        card.secureState()
    } catch (_: Exception) {
        null
    }

    fun syncIncomingWithCard(rustHistory: List<Transaction>): Set<String> {
        openSession()

        val lastReceived = cardSyncState.getLastReceived(currentUserId).toMutableMap()
        var updated = false

        val pendingByAuthor = unsyncedIncoming(rustHistory, currentUserId, lastReceived).groupBy { it.author }

        for ((author, authorTxs) in pendingByAuthor) {
            val senderGenesis = rustHistory.filterIsInstance<GenesisTransaction>().find { it.author == author }

            for ((_, _, _, seq, signature, _, goc) in authorTxs.sortedByDescending { it.goc }) {
                if (goc <= (lastReceived[author] ?: 0L)) continue

                try {
                    val senderPubKey = CoreWrapper.hexToBytes(senderGenesis?.publicKey ?: "")

                    val peerCertificate = senderGenesis?.certificateHex?.let { CoreWrapper.hexToBytes(it) }
                        ?: throw IllegalStateException("No certificate found for sender: $author")

                    ensurePeerRegistered(senderPubKey, peerCertificate)

                    val signatureBytes = CoreWrapper.hexToBytes(signature)

                    val myIdBytes = CoreWrapper.hexToBytes(currentUserId)

                    val logPayload = ProtocolSerializer.buildLogPayload(seq, Constants.OP_SEND, myIdBytes, goc)

                    val payload = ProtocolSerializer.buildReceivePayload(senderPubKey, signatureBytes, logPayload)

                    card.processReceive(payload)

                    lastReceived[author] = goc
                    updated = true
                } catch (_: Exception) { }
            }
        }

        if (updated) cardSyncState.saveLastReceived(currentUserId, lastReceived)

        return unsyncedIncoming(rustHistory, currentUserId, lastReceived).map { it.id }.toSet()
    }

    private fun ensurePeerRegistered(peerPubKey: ByteArray, peerCertificate: ByteArray) {
        try {
            val payload = ProtocolSerializer.buildAddPeerPayload(peerCertificate, peerPubKey)
            card.addPeer(payload)
        } catch (_: Exception) {
        }
    }

    fun mint(amount: Long) {
        openSession()
        val payload = ProtocolSerializer.buildMintBurnPayload(amount)
        recordOnLedger(card.processMint(payload), "Mint", CoreWrapper::mint)
    }

    fun burn(amount: Long) {
        openSession()
        val payload = ProtocolSerializer.buildMintBurnPayload(amount)
        recordOnLedger(card.processBurn(payload), "Burn", CoreWrapper::burn)
    }

    fun send(targetId: String, amount: Long, rustHistory: List<Transaction>) {
        require(amount > 0) { "Amount must be > 0" }
        openSession()

        val targetGenesis = rustHistory.filterIsInstance<GenesisTransaction>().find { it.author == targetId }
        val targetPubKeyHex = targetGenesis?.publicKey
            ?: throw IllegalStateException("Cannot send: Target peer certificate not discovered on ledger yet.")
        val targetPubKey = CoreWrapper.hexToBytes(targetPubKeyHex)

        val targetCertificate = targetGenesis.certificateHex?.let { CoreWrapper.hexToBytes(it) }
            ?: throw IllegalStateException("Cannot send: Target peer certificate not discovered on ledger yet.")

        ensurePeerRegistered(targetPubKey, targetCertificate)

        val payload = ProtocolSerializer.buildSendPayload(targetPubKey, amount)
        recordOnLedger(card.processSend(payload), "Send") { goc, seq, sigHex ->
            CoreWrapper.send(targetId, goc, seq, sigHex)
        }
    }

    private fun recordOnLedger(
        response: ByteArray,
        label: String,
        record: (goc: Long, seq: Long, sigHex: String) -> Boolean
    ) {
        val seq = ProtocolSerializer.parseSeqFromResponse(response)
        val goc = ProtocolSerializer.parseGocFromResponse(response)
        val sigHex = CoreWrapper.bytesToHex(ProtocolSerializer.parseSignatureFromResponse(response))

        if (!record(goc, seq, sigHex)) {
            throw IllegalStateException("$label was written to the card but the ledger refused it")
        }
    }

    fun close() {
        try {
            card.disconnect()
        } catch (_: Exception) {}
    }
}
