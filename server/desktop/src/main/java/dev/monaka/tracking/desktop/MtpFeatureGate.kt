package dev.monaka.tracking.desktop

object MtpFeatureGate {
	fun isEnabled(
		cli: Boolean = false,
		property: String? = System.getProperty("monaka.mtp.enabled"),
		environment: String? = System.getenv("MONAKA_MTP_ENABLED"),
	): Boolean {
		if (cli) return true
		return (property ?: environment)?.let {
			when (it.trim().lowercase()) {
				"true", "1", "yes", "on" -> true
				"false", "0", "no", "off" -> false
				else -> throw IllegalArgumentException("Invalid monaka.mtp.enabled / MONAKA_MTP_ENABLED value")
			}
		} ?: false
	}
}
