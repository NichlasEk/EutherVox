package se.euther.euthervox.app

// Also cover synthesis waits and paragraph gaps, before sound reaches AudioTrack.
internal fun keepScreenAwake(status: VoiceStatus): Boolean =
    status == VoiceStatus.Processing || status == VoiceStatus.Speaking
