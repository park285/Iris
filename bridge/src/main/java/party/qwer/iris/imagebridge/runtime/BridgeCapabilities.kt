package party.qwer.iris.imagebridge.runtime

internal fun currentBridgeCapabilities(
    registryAvailable: Boolean,
    registryError: String?,
    specReady: Boolean,
): ImageBridgeCapabilitiesSnapshot {
    val readinessReason =
        when {
            !registryAvailable -> registryError ?: "chatroom resolver unavailable"
            !specReady -> "bridge spec not ready"
            else -> "capability ready"
        }
    return ImageBridgeCapabilitiesSnapshot(
        inspectChatRoom =
            ImageBridgeCapabilitySnapshot(
                supported = registryAvailable,
                ready = registryAvailable && specReady,
                reason = if (registryAvailable && specReady) null else readinessReason,
            ),
        openChatRoom =
            ImageBridgeCapabilitySnapshot(
                supported = true,
                ready = true,
            ),
        snapshotChatRoomMembers =
            ImageBridgeCapabilitySnapshot(
                supported = registryAvailable,
                ready = registryAvailable && specReady,
                reason = if (registryAvailable && specReady) null else readinessReason,
            ),
    )
}
