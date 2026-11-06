package pl.infakt.doubleentry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PortInfoTest {
    @Test
    fun `names the gem and the version it is a port of`() {
        PortInfo.SOURCE_GEM shouldBe "double_entry"
        PortInfo.SOURCE_VERSION shouldBe "2.0.2"
    }
}
