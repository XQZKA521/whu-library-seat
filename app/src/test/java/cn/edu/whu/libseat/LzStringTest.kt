package cn.edu.whu.libseat

import cn.edu.whu.libseat.core.codec.LzString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试向量由前端同款 lz-string 1.5.0 生成：
 * LZString.compressToBase64(JSON.stringify(sample))
 */
class LzStringTest {

    private val plain =
        """{"objects":[{"type":"labeledImage","left":120,"top":80,"seat":{"id":"8910","label":"001","status":"FREE","window":true,"power":false,"direction":"bottom"}}],"name":"4F北区"}"""

    private val compressed =
        "N4Ig9gRgVgpgxgFwM4gFwG1QIJ4AcZogA2AhhDETACYCSAtiQOYEA0xMAZgmgIwBMABjYIwuNAA4hIJDBLdUoAJZVC4gJw8BINqXJFCAgT23SEcgK4pUIAGIAlAKIOTAd0UA7KmBdoEAJ3MYNlxvGD80DhIiGTYqRT94BEUwd0IIMAQROhAAXxyAXTZ3EjoCawAWG0B0ZUAuZVygA==="

    @Test
    fun decompressMatchesLzStringVector() {
        assertEquals(plain, LzString.decompressFromBase64(compressed))
    }

    @Test
    fun emptyInputHandled() {
        assertEquals("", LzString.decompressFromBase64(null))
    }
}
