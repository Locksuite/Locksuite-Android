package com.ejemplo.locksuite.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class IpPacketParserTest {

    private fun buildIpv4UdpPacket(
        srcIp: String = "10.0.0.2",
        dstIp: String = "10.0.0.1",
        srcPort: Int = 54321,
        dstPort: Int = 53,
        protocol: Int = IpPacketParser.PROTO_UDP,
        flagsAndFragOffset: Int = 0x4000, // DF=1, offset=0
        udpLengthOverride: Int? = null,
        payload: ByteArray = byteArrayOf(0x12, 0x34, 0x01, 0x00)
    ): ByteArray {
        val udpLength = udpLengthOverride ?: (8 + payload.size)
        val totalLength = 20 + (8 + payload.size)
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.put(0x45.toByte()) // v4, IHL=5 (20 bytes)
        buffer.put(0)
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0)
        buffer.putShort(flagsAndFragOffset.toShort())
        buffer.put(64.toByte())
        buffer.put(protocol.toByte())
        buffer.putShort(0)
        buffer.put(InetAddress.getByName(srcIp).address)
        buffer.put(InetAddress.getByName(dstIp).address)
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0)
        buffer.put(payload)
        return buffer.array()
    }

    private fun buildIpv6UdpPacket(
        srcIp: String = "fd00::2",
        dstIp: String = "fd00::1",
        srcPort: Int = 54321,
        dstPort: Int = 53,
        nextHeader: Int = IpPacketParser.PROTO_UDP,
        udpLengthOverride: Int? = null,
        payload: ByteArray = byteArrayOf(0x12, 0x34, 0x01, 0x00)
    ): ByteArray {
        val udpLength = udpLengthOverride ?: (8 + payload.size)
        val actualUdpBytes = 8 + payload.size
        val buffer = ByteBuffer.allocate(40 + actualUdpBytes)
        buffer.put(0x60.toByte())
        buffer.put(0)
        buffer.putShort(0)
        buffer.putShort(actualUdpBytes.toShort())
        buffer.put(nextHeader.toByte())
        buffer.put(64.toByte())
        buffer.put(InetAddress.getByName(srcIp).address)
        buffer.put(InetAddress.getByName(dstIp).address)
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0)
        buffer.put(payload)
        return buffer.array()
    }

    @Test
    fun parsesValidIpv4AndIpv6UdpPackets() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val v4 = buildIpv4UdpPacket(payload = payload)
        val parsedV4 = IpPacketParser.parse(v4, v4.size)
        assertNotNull(parsedV4)
        assertFalse(parsedV4!!.isIpv6)
        assertEquals(IpPacketParser.PROTO_UDP, parsedV4.protocol)
        assertEquals(InetAddress.getByName("10.0.0.2"), parsedV4.sourceIp)
        assertEquals(InetAddress.getByName("10.0.0.1"), parsedV4.destIp)
        assertEquals(54321, parsedV4.sourcePort)
        assertEquals(53, parsedV4.destPort)
        assertArrayEquals(payload, parsedV4.payload)

        val v6 = buildIpv6UdpPacket(payload = payload)
        val parsedV6 = IpPacketParser.parse(v6, v6.size)
        assertNotNull(parsedV6)
        assertTrue(parsedV6!!.isIpv6)
        assertEquals(InetAddress.getByName("fd00::2"), parsedV6.sourceIp)
        assertEquals(InetAddress.getByName("fd00::1"), parsedV6.destIp)
        assertEquals(53, parsedV6.destPort)
        assertArrayEquals(payload, parsedV6.payload)
    }

    @Test
    fun rejectsUdpLengthLessThanEight() {
        for (badLen in listOf(0, 1, 7)) {
            val v4 = buildIpv4UdpPacket(udpLengthOverride = badLen)
            assertNull("IPv4 con udpLength=$badLen debe rechazarse", IpPacketParser.parse(v4, v4.size))

            val v6 = buildIpv6UdpPacket(udpLengthOverride = badLen)
            assertNull("IPv6 con udpLength=$badLen debe rechazarse", IpPacketParser.parse(v6, v6.size))
        }
    }

    @Test
    fun rejectsFragmentedIpv4NonZeroOffset() {
        // Fragment offset = 1 (8 bytes) without MF
        val frag1 = buildIpv4UdpPacket(flagsAndFragOffset = 0x0001)
        assertNull(IpPacketParser.parse(frag1, frag1.size))

        // MF=1 + fragment offset = 8
        val frag2 = buildIpv4UdpPacket(flagsAndFragOffset = 0x2008)
        assertNull(IpPacketParser.parse(frag2, frag2.size))

        // MF=1 at offset 0: initial fragment has offset == 0 (accepted if UDP header present)
        val mfOffsetZero = buildIpv4UdpPacket(flagsAndFragOffset = 0x2000)
        assertNotNull(IpPacketParser.parse(mfOffsetZero, mfOffsetZero.size))
    }

    @Test
    fun rejectsNonUdpAndTruncatedPackets() {
        val tcpV4 = buildIpv4UdpPacket(protocol = 6)
        assertNull(IpPacketParser.parse(tcpV4, tcpV4.size))

        val tcpV6 = buildIpv6UdpPacket(nextHeader = 6)
        assertNull(IpPacketParser.parse(tcpV6, tcpV6.size))

        val validV4 = buildIpv4UdpPacket()
        assertNull(IpPacketParser.parse(validV4, 19))
        assertNull(IpPacketParser.parse(validV4, 27)) // cuts UDP header
    }
}
