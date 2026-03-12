package dole.card

import dole.Constants
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PCSmartCard : SmartCard {
    private var serviceProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isConnectedInternal = false

    override val isConnected: Boolean get() = isConnectedInternal && serviceProcess?.isAlive == true

    @Synchronized
    private fun startService() {
        if (serviceProcess?.isAlive == true) return
        killService()

        try {
            val javaHome = System.getProperty("java.home")
            val os = System.getProperty("os.name").lowercase(Locale.getDefault())
            val javaBin = javaHome + (if (os.contains("win")) "/bin/java.exe" else "/bin/java")
            val classpath = System.getProperty("java.class.path")

            val process = ProcessBuilder(javaBin, "-cp", classpath, "dole.card.SmartCardService").start()
            serviceProcess = process

            writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            reader = BufferedReader(InputStreamReader(process.inputStream))

            thread(isDaemon = true) {
                try {
                    val errReader = BufferedReader(InputStreamReader(process.errorStream))
                    while (process.isAlive) {
                        errReader.readLine() ?: break
                    }
                } catch (_: Exception) {}
            }

            val line = reader?.readLine()
            if ("READY" != line) {
                throw Exception("Service crashed on start: $line")
            }
        } catch (e: Exception) {
            killService()
            throw Exception("Failed to start service: ${e.message}")
        }
    }

    @Synchronized
    private fun killService() {
        if (serviceProcess == null) return
        try {
            writer?.apply {
                write("EXIT")
                newLine()
                flush()
            }
        } catch (_: Exception) {}

        serviceProcess?.apply {
            destroy()
            try {
                if (!waitFor(1, TimeUnit.SECONDS)) destroyForcibly()
            } catch (_: InterruptedException) {
                destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }

        serviceProcess = null
        writer = null
        reader = null
        isConnectedInternal = false
    }

    @Synchronized
    private fun sendCommand(cmd: String): String {
        if (serviceProcess?.isAlive != true) startService()

        try {
            writer?.apply {
                write(cmd)
                newLine()
                flush()
            }

            val response = reader?.readLine() ?: throw Exception("EOF")

            if (response.startsWith("ERROR:")) {
                val msg = response.substring(6)

                val isHarmless = msg.contains(Constants.ERR_CODE_NO_READER) ||
                        msg.contains(Constants.ERR_CARD_NOT_FOUND) ||
                        msg.contains("PCSCException") ||
                        msg.contains("CardException") ||
                        msg.contains("0x8010002f") ||
                        msg.contains("0x80100069") ||
                        msg.contains("0x1f") ||
                        msg.contains("0x16") ||
                        msg.contains("SCARD_")

                if (isHarmless) {
                    throw Exception(Constants.ERR_CARD_NOT_FOUND)
                } else {
                    killService()
                    throw Exception(msg)
                }
            }

            if (response.startsWith("OK:")) return response.substring(3)
            throw Exception("Protocol Error: $response")
        } catch (e: Exception) {
            if (e.message?.contains(Constants.ERR_CARD_NOT_FOUND) != true) killService()
            throw e
        }
    }

    override fun connect() {
        try {
            val resp = sendCommand("CONNECT")
            if ("CONNECTED" != resp) throw Exception("Connect failed: $resp")
            isConnectedInternal = true
        } catch (e: Exception) {
            isConnectedInternal = false
            throw e
        }
    }

    override fun disconnect() {
        try {
            if (isConnected) sendCommand("DISCONNECT")
        } catch (_: Exception) {}
        isConnectedInternal = false
    }

    private fun transmitInternal(cmd: CommandAPDU): ByteArray {
        val b64Cmd = Base64.getEncoder().encodeToString(cmd.bytes)
        val rawResp = sendCommand("TRANSMIT:$b64Cmd")

        val splitIdx = rawResp.lastIndexOf('|')
        if (splitIdx == -1) throw Exception("Invalid response: $rawResp")

        val b64Data = rawResp.substring(0, splitIdx)
        val sw = rawResp.substring(splitIdx + 1).toInt()

        if (sw != 0x9000) throw Exception("Card Error SW: ${Integer.toHexString(sw)}")
        return if (b64Data.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(b64Data)
    }

    override fun verifyPin(pin: ByteArray): Boolean {
        return try {
            transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_VERIFY_PIN.toInt(), 0x00, 0x00, pin))
            true
        } catch (e: Exception) {
            if (e.message?.contains("Card Error") == true) false else throw e
        }
    }

    override fun changePin(newPin: ByteArray): Boolean {
        return try {
            transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_CHANGE_PIN.toInt(), 0x00, 0x00, newPin))
            true
        } catch (e: Exception) {
            if (e.message?.contains("Card Error") == true) false else throw e
        }
    }

    override val publicKey: ByteArray
        get() = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_PUBKEY.toInt(), 0x00, 0x00))

    override val certificate: ByteArray
        get() = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_CERT.toInt(), 0x00, 0x00))

    override fun processGenesis():ByteArray = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GENESIS.toInt(), 0x00, 0x00, ByteArray(0)))
    override fun processMint(payload: ByteArray): ByteArray = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_MINT.toInt(), 0x00, 0x00, payload))
    override fun processBurn(payload: ByteArray): ByteArray = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_BURN.toInt(), 0x00, 0x00, payload))
    override fun processSend(payload: ByteArray): ByteArray = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_SEND.toInt(), 0x00, 0x00, payload))

    override fun processReceive(payload: ByteArray) {
        transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_RECEIVE.toInt(), 0x00, 0x00, payload))
    }

    override fun addPeer(payload: ByteArray) {
        transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_ADD_PEER.toInt(), 0x00, 0x00, payload))
    }

    override val isMinter: Boolean get() {
        val data = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_STATUS.toInt(), 0x00, 0x00, 256))
        return data.isNotEmpty() && data[0] == 0x01.toByte()
    }

    override val isPinSet: Boolean get() {
        val data = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_STATUS.toInt(), 0x00, 0x00, 256))
        return data.size > 1 && data[1] == 0x01.toByte()
    }

    override val isGenesisDone: Boolean get() {
        val data = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_STATUS.toInt(), 0x00, 0x00, 256))
        return data.size > 2 && data[2] == 0x01.toByte()
    }

    override val pinRetries: Int get() {
        val data = transmitInternal(CommandAPDU(Constants.CLA_PROPRIETARY, Constants.OP_GET_STATUS.toInt(), 0x00, 0x00, 256))
        return if (data.size > 3) data[3].toInt() else 3
    }
}

class CommandAPDU(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray? = null, ne: Int = 0) {
    val bytes: ByteArray
    init {
        val hasData = data != null && data.isNotEmpty()
        val len = 4 + (if (hasData) 1 + data.size else 0) + (if (ne > 0) 1 else 0)
        bytes = ByteArray(len)
        bytes[0] = cla.toByte()
        bytes[1] = ins.toByte()
        bytes[2] = p1.toByte()
        bytes[3] = p2.toByte()
        var offset = 4
        if (hasData) {
            bytes[offset++] = data.size.toByte()
            data.copyInto(bytes, offset)
            offset += data.size
        }
        if (ne > 0) bytes[offset] = if (ne == 256) 0.toByte() else ne.toByte()
    }

    constructor(cla: Int, ins: Int, p1: Int, p2: Int) : this(cla, ins, p1, p2, null, 0)
    constructor(cla: Int, ins: Int, p1: Int, p2: Int, ne: Int) : this(cla, ins, p1, p2, null, ne)
    constructor(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray) : this(cla, ins, p1, p2, data, 0)
}