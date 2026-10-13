package cn.renewboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Catalog112Test {
    @Test fun apiBalancesDoNotMatchConsumerMemberships() {
        val names = mapOf(
            "OpenAI API 余额" to "OpenAI API",
            "ChatGPT API" to "OpenAI API",
            "Claude API" to "Anthropic API",
            "Anthropic Claude API" to "Anthropic API",
            "Gemini API 余额" to "Gemini API",
            "Google AI Studio" to "Gemini API",
        )
        names.forEach { (input, expected) ->
            val preset = requireNotNull(servicePresetFor(input))
            assertEquals(input, expected, preset.name)
            assertTrue(input, preset.usageBalance)
        }
        listOf("ChatGPT Plus", "Claude Pro", "Gemini Advanced").forEach {
            assertFalse(it, requireNotNull(servicePresetFor(it)).usageBalance)
        }
    }

    @Test fun domesticApiBalancesAreDiscoverable() {
        mapOf(
            "DeepSeek" to "DeepSeek API",
            "小米 MiMo" to "MiMo API",
            "DashScope" to "阿里云百炼",
            "豆包 API" to "火山方舟",
            "SiliconFlow" to "硅基流动",
        ).forEach { (input, expected) ->
            val preset = requireNotNull(servicePresetFor(input))
            assertEquals(expected, preset.name)
            assertEquals("CNY", preset.currency)
            assertTrue(preset.usageBalance)
        }
    }

    @Test fun cloudServicesUseAppropriateCycles() {
        val server = requireNotNull(servicePresetFor("VPS 服务器"))
        assertEquals("服务器", server.name)
        assertEquals(Cycle.MONTH, server.defaultCycle)
        assertFalse(server.usageBalance)
        val domain = requireNotNull(servicePresetFor("Domain 域名续费"))
        assertEquals("域名", domain.name)
        assertEquals(Cycle.YEAR, domain.defaultCycle)
        assertFalse(domain.usageBalance)
    }
}
