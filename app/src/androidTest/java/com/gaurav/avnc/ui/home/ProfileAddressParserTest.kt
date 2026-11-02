/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAddressParserTest {
    @Test fun hostsAndExplicitPortsReuseVncUriParsing() {
        assertEquals(ProfileAddress("desktop.local", null, false), parseProfileAddress(" desktop.local "))
        assertEquals(ProfileAddress("192.0.2.1", 5901, false), parseProfileAddress("192.0.2.1:5901"))
        assertEquals(ProfileAddress("desktop.local", 6000, false), parseProfileAddress("VNC://desktop.local:6000/"))
        assertEquals(ProfileAddress("desktop.local", null, false), parseProfileAddress("vnc:desktop.local"))
    }

    @Test fun ipv6IsAWholeLiteralUnlessAPortIsBracketed() {
        for (input in listOf("2001:db8::1", "[2001:db8::1]", "vnc://2001:db8::1"))
            assertEquals(input, ProfileAddress("2001:db8::1", null, false), parseProfileAddress(input))
        assertEquals(ProfileAddress("2001:db8::1", 5901, false), parseProfileAddress("vnc://[2001:db8::1]:5901"))
        assertEquals(ProfileAddress("2001:db8::5901", null, false), parseProfileAddress("2001:db8::5901"))
    }

    @Test fun uriOptionsAreDetectedWithoutImportingThem() {
        val parsed = parseProfileAddress("vnc://desktop.local:5902/?VncPassword=secret&ChannelType=24&SshPort=2222")!!
        assertEquals("desktop.local", parsed.host)
        assertEquals(5902, parsed.port)
        assertTrue(parsed.hasSettings)
    }

    @Test fun malformedLinksAddressesAndPortsAreRejected() {
        for (input in listOf("", "  ", "https://desktop.local", "vnc://", "bad host", "host:",
                "host:abc", "host:0", "host:65536", "[2001:db8::1]:", "[fe80:2a10:b62b]",
                "vnc://user:secret@desktop.local"))
            assertNull(input, parseProfileAddress(input))
    }
}
