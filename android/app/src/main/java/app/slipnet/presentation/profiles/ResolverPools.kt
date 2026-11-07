package app.slipnet.presentation.profiles

/** The three lifecycle pools a DNS resolver can live in. */
enum class ResolverPool(val title: String) {
    /** The profile's own resolver list: what the tunnel uses right now. */
    ACTIVE("Active"),

    /** Shared candidate pool: scanned at connect time, the best ones are promoted to Active. */
    STANDBY("Standby"),

    /** Scanner output saved with "Save as resolver pool": promoted with one tap. */
    HARVESTED("Harvested")
}

/** UI state for Standby / Harvested (Active lives in the profile's own ui state). */
data class ResolverPoolsState(
    val standby: List<String> = emptyList(),
    val harvested: List<String> = emptyList(),
    val harvestedLabel: String? = null,
    val autoPick: Boolean = false,
    /** One-line result of the last import / move, shown under the list. */
    val message: String? = null
)
