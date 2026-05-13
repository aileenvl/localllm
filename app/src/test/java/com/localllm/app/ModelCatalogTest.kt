package com.localllm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `Gemma 4 entries are not SoC-gated`() {
        val gemma4 = AVAILABLE_MODELS.first { it.id == "gemma-4-e2b" }
        assertNull(gemma4.requiredSocMarker)
        assertNull(gemma4.npuSocLabel())
        // Non-NPU models always match the current device.
        assertTrue(gemma4.matchesCurrentSoc())
    }

    @Test
    fun `every advertised NPU SoC has a human label`() {
        val npu = AVAILABLE_MODELS.filter { it.requiredSocMarker != null }
        // 4 Snapdragon + 3 MediaTek per the LiteRT-LM NPU guide.
        assertEquals(7, npu.size)
        for (m in npu) {
            val label = m.npuSocLabel()
            assertNotNull("missing label for ${m.id}", label)
            // SoC marker should appear (uppercase) in the human label so a
            // power-user can still cross-reference Build.SOC_MODEL.
            val marker = m.requiredSocMarker!!.uppercase()
            assertTrue("label '$label' must contain '$marker'", label!!.contains(marker))
        }
    }

    @Test
    fun `NPU filenames are stable and unique`() {
        val npuFilenames = AVAILABLE_MODELS
            .filter { it.requiredSocMarker != null }
            .map { it.filename }
        assertEquals(npuFilenames.size, npuFilenames.toSet().size)
        npuFilenames.forEach { fn ->
            assertTrue("$fn should end in .litertlm", fn.endsWith(".litertlm"))
            assertTrue("$fn should signal NPU", fn.contains("npu"))
        }
    }

    @Test
    fun `NPU URLs point at the litert-community Gemma3-1B-IT repo`() {
        val npu = AVAILABLE_MODELS.filter { it.requiredSocMarker != null }
        for (m in npu) {
            val expectedPrefix =
                "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/"
            assertTrue(
                "URL for ${m.id} should start with $expectedPrefix but was ${m.url}",
                m.url.startsWith(expectedPrefix)
            )
            // The on-disk filename diverges from the upstream filename (we
            // namespace ours with `npu-` for clarity), but the upstream
            // filename in the URL must still carry the SoC marker.
            assertTrue(
                "upstream URL for ${m.id} must reference SoC ${m.requiredSocMarker}",
                m.url.contains(m.requiredSocMarker!!)
            )
        }
    }
}
