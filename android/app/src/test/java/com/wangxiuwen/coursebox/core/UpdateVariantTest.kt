package com.wangxiuwen.coursebox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.33 shipped two apks and every build older than it took "the first
 * apk in the release" — GitHub sorts assets by name, so phones updated
 * themselves into the kiosk image. These pin the rules that replaced that.
 */
class UpdateVariantTest {

    private fun asset(name: String) = GhAsset(name = name, browser_download_url = "u/$name", size = 1)

    private val release = listOf(
        asset("coursebox-android-v0.10.34.apk"),
        asset("coursebox-kiosk-android-v0.10.34.apk"),
        asset("coursebox-packager-linux-x64.deb"),
    )

    @Test
    fun `normal build defaults to the normal image but may switch`() {
        val v = UpdateChecker.variantsFor(release, tag = "")
        assertEquals(2, v.size)
        assertEquals("coursebox-android-v0.10.34.apk", v.first().asset.name)
        assertTrue(v.first().isOurs)
        assertTrue(v[1].isKiosk)
    }

    @Test
    fun `kiosk build defaults to the kiosk image`() {
        val v = UpdateChecker.variantsFor(release, tag = "kiosk")
        assertEquals("coursebox-kiosk-android-v0.10.34.apk", v.first().asset.name)
        assertTrue(v.first().isOurs)
    }

    /** A provisioned kiosk device must never be handed the normal apk. */
    @Test
    fun `a locked kiosk device is offered only the kiosk image`() {
        val v = UpdateChecker.variantsFor(release, tag = "kiosk", allowFlavourChange = false)
        assertEquals(1, v.size)
        assertTrue(v.single().isKiosk)
    }

    /** Pre-split releases carry one untagged apk — no kiosk image to take. */
    @Test
    fun `a locked kiosk device is offered nothing by a pre-split release`() {
        val v = UpdateChecker.variantsFor(
            listOf(asset("coursebox-android-v0.10.32.apk")),
            tag = "kiosk",
            allowFlavourChange = false,
        )
        assertTrue(v.isEmpty())
    }

    @Test
    fun `unsigned and non-apk assets are ignored`() {
        val v = UpdateChecker.variantsFor(
            release + asset("coursebox-android-v0.10.34-unsigned.apk"),
            tag = "",
        )
        assertEquals(2, v.size)
    }
}
