package fyi.b612.lovehouse.feature.chat

import fyi.b612.lovehouse.core.capability.SpeechToTextController
import fyi.b612.lovehouse.core.capability.SpeechToTextState

internal typealias ChatVoiceInputState = SpeechToTextState
internal typealias ChatVoiceInputController = SpeechToTextController
internal const val STT_UNAVAILABLE_MESSAGE = fyi.b612.lovehouse.core.capability.STT_UNAVAILABLE_MESSAGE

internal enum class ChatVoiceComposerMode { Text, VoiceReady, Listening, Review }

internal enum class ChatVoiceComposerAction { EnterVoice, Press, ReleaseWithTranscript, Cancel, SendOrClear }

internal fun transitionVoiceComposer(
    mode: ChatVoiceComposerMode,
    action: ChatVoiceComposerAction,
    hasTranscript: Boolean = false,
): ChatVoiceComposerMode = when (action) {
    ChatVoiceComposerAction.EnterVoice -> ChatVoiceComposerMode.VoiceReady
    ChatVoiceComposerAction.Press -> if (mode == ChatVoiceComposerMode.VoiceReady) ChatVoiceComposerMode.Listening else mode
    ChatVoiceComposerAction.ReleaseWithTranscript -> if (hasTranscript) ChatVoiceComposerMode.Review else ChatVoiceComposerMode.VoiceReady
    ChatVoiceComposerAction.Cancel, ChatVoiceComposerAction.SendOrClear -> ChatVoiceComposerMode.Text
}

internal fun ChatVoiceInputState.composerTranscriptOrNull(): String? =
    transcript.trim().takeIf { finished && it.isNotEmpty() }
