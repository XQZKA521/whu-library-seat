package cn.edu.whu.libseat.core.codec

/**
 * LZString 的 Base64 解压实现（仅解压）。
 *
 * 新版座位系统的 `querySeatLayout` 返回的是
 * LZString.compressToBase64(JSON + "_updVersion_" + version)，
 * 前端用 lz-string 解码后交给 Fabric.js 渲染。
 */
object LzString {

    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    fun decompressFromBase64(input: String?): String? {
        if (input == null) return ""
        if (input.isEmpty()) return null
        return decompress(input.length, 32) { index ->
            if (index !in input.indices) -1 else BASE64.indexOf(input[index])
        }
    }

    private fun decompress(length: Int, resetValue: Int, nextValue: (Int) -> Int): String? {
        val dictionary = HashMap<Int, String>()
        var enlargeIn = 4
        var dictSize = 4
        var numBits = 3
        val result = ArrayList<String>()
        var dataVal = nextValue(0)
        var dataPosition = resetValue
        var dataIndex = 1

        fun readBits(bits: Int): Int {
            var value = 0
            var power = 1
            val maxPower = 1 shl bits
            while (power != maxPower) {
                val bit = dataVal and dataPosition
                dataPosition = dataPosition shr 1
                if (dataPosition == 0) {
                    dataPosition = resetValue
                    dataVal = nextValue(dataIndex++)
                }
                if (bit > 0) value = value or power
                power = power shl 1
            }
            return value
        }

        for (i in 0..2) dictionary[i] = i.toString()

        var entry: String = when (readBits(2)) {
            0 -> readBits(8).toChar().toString()
            1 -> readBits(16).toChar().toString()
            else -> return ""
        }
        dictionary[3] = entry
        result.add(entry)

        while (true) {
            if (dataIndex > length) return ""
            var code = readBits(numBits)
            when (code) {
                0 -> {
                    dictionary[dictSize++] = readBits(8).toChar().toString()
                    code = dictSize - 1
                    enlargeIn--
                }

                1 -> {
                    dictionary[dictSize++] = readBits(16).toChar().toString()
                    code = dictSize - 1
                    enlargeIn--
                }

                2 -> return result.joinToString("")
            }
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }

            val current: String = dictionary[code] ?: run {
                if (code == dictSize) entry + entry.take(1) else return null
            }
            result.add(current)
            dictionary[dictSize++] = entry + current.take(1)
            entry = current

            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }
    }
}
