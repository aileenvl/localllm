package com.localllm.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = "com.localllm.app") {
        pressHome()
        startActivityAndWait()
        // Wait for the tab bar to compose so we collect the Catalog screen path.
        device.wait(Until.hasObject(By.text("Catalog")), 10_000)
        // Touch every tab so the profile covers the hot composables.
        listOf("Dashboard", "Console", "Chat", "Settings").forEach { label ->
            device.findObject(By.text(label))?.click()
            device.waitForIdle()
        }
    }
}
