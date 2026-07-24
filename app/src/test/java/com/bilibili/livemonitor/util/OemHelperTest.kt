package com.bilibili.livemonitor.util

import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * 国产 ROM 识别表（B6）。
 * 真机场景：用户设备是小米/OPPO/vivo/华为/荣耀时，
 * 必须弹出自启动/后台管理引导，否则后台监控必被杀。
 */
@RunWith(RobolectricTestRunner::class)
class OemHelperTest {

    private val originalManufacturer: String = Build.MANUFACTURER

    @After
    fun tearDown() {
        setManufacturer(originalManufacturer)
    }

    private fun setManufacturer(value: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", value)
    }

    @Test
    fun `小米 识别为MIUI`() {
        setManufacturer("Xiaomi")
        val info = OemHelper.getOemInfo()
        assertNotNull(info)
        assertTrue(info!!.displayName.contains("小米"))
        assertTrue(info.intents.isNotEmpty())
    }

    @Test
    fun `红米 识别为MIUI`() {
        setManufacturer("Redmi")
        assertNotNull(OemHelper.getOemInfo())
    }

    @Test
    fun `OPPO 识别`() {
        setManufacturer("OPPO")
        val info = OemHelper.getOemInfo()
        assertNotNull(info)
        assertTrue(info!!.displayName.contains("OPPO"))
    }

    @Test
    fun `realme 识别为OPPO系`() {
        setManufacturer("realme")
        assertNotNull(OemHelper.getOemInfo())
    }

    @Test
    fun `vivo 识别`() {
        setManufacturer("vivo")
        val info = OemHelper.getOemInfo()
        assertNotNull(info)
        assertTrue(info!!.displayName.contains("vivo"))
    }

    @Test
    fun `华为 识别`() {
        setManufacturer("HUAWEI")
        val info = OemHelper.getOemInfo()
        assertNotNull(info)
        assertTrue(info!!.displayName.contains("华为"))
    }

    @Test
    fun `荣耀 识别`() {
        setManufacturer("HONOR")
        val info = OemHelper.getOemInfo()
        assertNotNull(info)
        assertTrue(info!!.displayName.contains("荣耀"))
    }

    @Test
    fun `三星 识别`() {
        setManufacturer("samsung")
        val info = OemHelper.getOemInfo()
        assertNotNull(info)
        assertTrue(info!!.displayName.contains("三星"))
    }

    @Test
    fun `Google原生机 返回null不打扰`() {
        // 真机场景：Pixel/模拟器用户不需要 OEM 引导弹窗
        setManufacturer("Google")
        assertNull(OemHelper.getOemInfo())
        assertEquals(false, OemHelper.isProblematicOem())
    }

    @Test
    fun `未知厂商 返回null`() {
        setManufacturer("SomeUnknownBrand")
        assertNull(OemHelper.getOemInfo())
    }
}
