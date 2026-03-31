package dole.card

import dole.Constants
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import kotlin.system.exitProcess

object SmartCardService {
    private var cardObj: Any? = null
    private var channelObj: Any? = null

    private fun unwrap(e: Throwable): Throwable {
        var cause = e
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return cause
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            println("READY")
            val reader = BufferedReader(InputStreamReader(System.`in`))

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (line == "EXIT") break

                try {
                    when {
                        line == "CONNECT" -> doConnect()
                        line == "DISCONNECT" -> doDisconnect()
                        line.startsWith("TRANSMIT:") -> doTransmit(line.substring(9))
                        else -> println("ERROR:Unknown Command")
                    }
                } catch (e: Exception) {
                    val root = unwrap(e)
                    val msg = root.message ?: root.toString()
                    println("ERROR:$msg")
                }
            }
        } catch (_: Throwable) {}
        exitProcess(0)
    }

    private fun cleanupSilent() {
        if (cardObj != null) {
            try {
                val cardClass = Class.forName("javax.smartcardio.Card")
                val disconnectMethod = cardClass.getMethod("disconnect", Boolean::class.javaPrimitiveType)
                disconnectMethod.invoke(cardObj, false)
            } catch (_: Exception) {}
        }
        cardObj = null
        channelObj = null
    }

    private fun doConnect() {
        cleanupSilent()
        try {
            val factoryClass = Class.forName("javax.smartcardio.TerminalFactory")
            val getDefault = factoryClass.getMethod("getDefault")
            val factory = getDefault.invoke(null)

            val terminalsMethod = factoryClass.getMethod("terminals")
            val cardTerminals = terminalsMethod.invoke(factory)

            val cardTerminalsClass = Class.forName("javax.smartcardio.CardTerminals")
            val listMethod = cardTerminalsClass.getMethod("list")

            val terminalsList = try {
                listMethod.invoke(cardTerminals) as List<*>
            } catch (e: Exception) {
                val cause = unwrap(e)
                throw Exception("DRIVER_CRASH:${cause.message}")
            }

            if (terminalsList.isEmpty()) throw Exception(Constants.ERR_CODE_NO_READER)

            val cardTerminalClass = Class.forName("javax.smartcardio.CardTerminal")
            val isCardPresentMethod = cardTerminalClass.getMethod("isCardPresent")
            val connectMethod = cardTerminalClass.getMethod("connect", String::class.java)

            val cardClass = Class.forName("javax.smartcardio.Card")
            val getBasicChannelMethod = cardClass.getMethod("getBasicChannel")

            val cardChannelClass = Class.forName("javax.smartcardio.CardChannel")

            val commandApduClass = Class.forName("javax.smartcardio.CommandAPDU")
            val cmdConstructor = commandApduClass.getConstructor(ByteArray::class.java)
            val transmitMethod = cardChannelClass.getMethod("transmit", commandApduClass)

            val responseApduClass = Class.forName("javax.smartcardio.ResponseAPDU")
            val getSWMethod = responseApduClass.getMethod("getSW")

            var found = false
            for (terminal in terminalsList) {
                if (terminal == null) continue

                val isPresent = isCardPresentMethod.invoke(terminal) as Boolean

                if (isPresent) {
                    try {
                        val c = connectMethod.invoke(terminal, "*")
                        val ch = getBasicChannelMethod.invoke(c)

                        val aid = Constants.APPLET_AID_BYTES
                        val selectCmd = ByteArray(5 + aid.size)
                        selectCmd[0] = 0x00
                        selectCmd[1] = 0xA4.toByte()
                        selectCmd[2] = 0x04
                        selectCmd[3] = 0x00
                        selectCmd[4] = aid.size.toByte()
                        aid.copyInto(selectCmd, 5)

                        val cmdApdu = cmdConstructor.newInstance(selectCmd)
                        val respApdu = transmitMethod.invoke(ch, cmdApdu)

                        val sw = getSWMethod.invoke(respApdu) as Int

                        if (sw == 0x9000) {
                            cardObj = c
                            channelObj = ch
                            found = true
                            break
                        } else {
                            try {
                                val disconnectMethod = cardClass.getMethod("disconnect", Boolean::class.javaPrimitiveType)
                                disconnectMethod.invoke(c, false)
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }

            if (!found) {
                cleanupSilent()
                throw Exception(Constants.ERR_CARD_NOT_FOUND)
            }

            println("OK:CONNECTED")
        } catch (e: Exception) {
            throw unwrap(e)
        }
    }

    private fun doDisconnect() {
        cleanupSilent()
        println("OK:DISCONNECTED")
    }

    private fun doTransmit(payload: String) {
        if (channelObj == null) {
            throw Exception(Constants.ERR_CARD_NOT_FOUND)
        }

        try {
            val cmdBytes = Base64.getDecoder().decode(payload)

            val commandApduClass = Class.forName("javax.smartcardio.CommandAPDU")
            val cmdConstructor = commandApduClass.getConstructor(ByteArray::class.java)
            val cmdApdu = cmdConstructor.newInstance(cmdBytes)

            val cardChannelClass = Class.forName("javax.smartcardio.CardChannel")
            val transmitMethod = cardChannelClass.getMethod("transmit", commandApduClass)

            val respApdu = transmitMethod.invoke(channelObj, cmdApdu)

            val responseApduClass = Class.forName("javax.smartcardio.ResponseAPDU")
            val getDataMethod = responseApduClass.getMethod("getData")
            val getSWMethod = responseApduClass.getMethod("getSW")

            val dataBytes = getDataMethod.invoke(respApdu) as ByteArray
            val sw = getSWMethod.invoke(respApdu) as Int

            val outB64 = Base64.getEncoder().encodeToString(dataBytes)
            println("OK:$outB64|$sw")

        } catch (e: Exception) {
            val cause = unwrap(e)
            cleanupSilent()
            throw cause
        }
    }
}