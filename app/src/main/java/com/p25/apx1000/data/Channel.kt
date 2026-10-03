package com.p25.apx1000.data

/** A talkgroup channel shown on the APX1000 Zone/Channel display. */
data class Channel(
    val name: String,
    val code: String,
    val zone: String = "ZONE 1"
) {
    val displayName: String get() = name

    /** True when the user typed a unique channel code rather than a name. */
    val isCodeEntry: Boolean get() = code.equals(name, ignoreCase = true)

    companion object {
        /** Codes look like "P25-CH-8891". */
        private val CODE_REGEX = Regex("^P25-CH-\\d{3,6}$", RegexOption.IGNORE_CASE)

        fun isUniqueCode(input: String): Boolean = CODE_REGEX.matches(input.trim())

        fun fromNameOrCode(input: String): Channel {
            val value = input.trim()
            return if (isUniqueCode(value)) {
                Channel(name = value.uppercase(), code = value.uppercase())
            } else {
                Channel(name = value, code = "TG-${value.uppercase().hashCode().toUInt().toString(16)}")
            }
        }
    }
}
