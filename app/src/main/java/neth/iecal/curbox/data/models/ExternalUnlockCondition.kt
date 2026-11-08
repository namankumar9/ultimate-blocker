package neth.iecal.curbox.data.models

/** A provider owned condition selected by the user in a warning configuration. */
data class ExternalUnlockCondition(
    val packageName: String = "",
    val id: String = "",
    val label: String = "",
    val activityName: String = "",
    val signingCertificates: List<String> = emptyList()
)
