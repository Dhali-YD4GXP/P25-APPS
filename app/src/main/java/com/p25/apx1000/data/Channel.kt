package com.p25.apx1000.data

/** A talkgroup channel shown on the APX1000 Zone/Channel display. */
data class Channel(
    val name: String,
    val code: String,
    val zone: Int = 1,
    val alias: String? = null
) {
    val displayName: String get() = alias ?: name

    companion object {
        /** Codes look like "P25-CH-8891". */
        private val CODE_REGEX = Regex("^P25-CH-\\d{3,6}$", RegexOption.IGNORE_CASE)
        private val DIGITS_REGEX = Regex("^\\d{3,6}$")

        fun isUniqueCode(input: String): Boolean = CODE_REGEX.matches(input.trim())

        /**
         * Accepts a unique code (`P25-CH-8891`), a bare numeric code
         * (`8891` -> `P25-CH-8891`, keypad-only friendly) or a free name.
         */
        fun fromNameOrCode(input: String): Channel {
            val value = input.trim()
            return when {
                isUniqueCode(value) -> {
                    val code = value.uppercase()
                    Channel(name = code, code = code)
                }
                DIGITS_REGEX.matches(value) -> {
                    val code = "P25-CH-$value"
                    Channel(name = code, code = code)
                }
                else -> Channel(name = value, code = "TG-${value.uppercase().hashCode().toUInt().toString(16)}")
            }
        }
    }
}
