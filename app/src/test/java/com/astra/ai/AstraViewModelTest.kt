package com.astra.ai

import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

class AstraViewModelTest {
    @Test
    fun localEngineResponds() = runBlocking {
        assertEquals("Hello. I'm Astra.", LocalAiEngine().respond("hello"))
    }
}
