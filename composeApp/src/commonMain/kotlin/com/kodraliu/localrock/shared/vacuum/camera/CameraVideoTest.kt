package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.webrtc.RtcOfferOptions

/** Temporary, explicit experiments. Each step changes one setting from the preceding test. */
enum class CameraVideoTest(
    val code: String,
    val label: String,
    val description: String,
    val previewQuality: String,
    val offerOptions: RtcOfferOptions,
) {
    CURRENT("A", "Current", "Same setup as build 1.40 (1).", "HD", RtcOfferOptions()),
    AUDIO_FIRST("B", "Audio first", "Changes the media order from A.", "HD", RtcOfferOptions(audioFirst = true)),
    OEM_LAYOUT("C", "OEM layout", "Adds the OEM video direction to B.", "HD",
        RtcOfferOptions(audioFirst = true, sendReceiveVideo = true)),
    OEM_LAYOUT_SD("D", "OEM layout · SD", "Uses C with SD preview quality.", "SD",
        RtcOfferOptions(audioFirst = true, sendReceiveVideo = true)),
}
