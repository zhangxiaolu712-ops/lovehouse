export const UNSUPPORTED_ATTACHMENT_CAPABILITIES = Object.freeze({
  accepted_types: Object.freeze([]),
  max_items: 0,
  supports_text_with_attachments: false,
  supported_lifecycles: Object.freeze([]),
})

export const CODEX_ATTACHMENT_CAPABILITIES = Object.freeze({
  accepted_types: Object.freeze(['photo', 'file', 'location']),
  max_items: 12,
  supports_text_with_attachments: true,
  supported_lifecycles: Object.freeze(['LOCAL', 'EPHEMERAL']),
})

function inferredLifecycle(attachment) {
  if (typeof attachment?.lifecycle === 'string' && attachment.lifecycle.trim()) {
    return attachment.lifecycle.trim().toUpperCase()
  }
  return attachment?.type === 'location' ? 'LOCAL' : 'EPHEMERAL'
}

export function attachmentCapabilitiesFrom(resolvedProvider) {
  return resolvedProvider?.persona?.runtime?.capabilities?.attachments
    || UNSUPPORTED_ATTACHMENT_CAPABILITIES
}

export function validateAttachmentRequest({ attachments, text, capabilities, error }) {
  if (!Array.isArray(attachments) || attachments.length === 0) return

  const acceptedTypes = new Set(capabilities?.accepted_types || [])
  const supportedLifecycles = new Set(capabilities?.supported_lifecycles || [])
  if (acceptedTypes.size === 0 || !(capabilities?.max_items > 0)) {
    throw error('ATTACHMENTS_UNSUPPORTED', 'attachments are not enabled for this provider', 415)
  }
  if (attachments.length > capabilities.max_items) {
    throw error(
      'ATTACHMENT_LIMIT_EXCEEDED',
      `provider accepts at most ${capabilities.max_items} attachments per turn`,
      400,
    )
  }
  if (text && capabilities.supports_text_with_attachments !== true) {
    throw error(
      'ATTACHMENT_TEXT_UNSUPPORTED',
      'provider does not support text and attachments in the same turn',
      415,
    )
  }
  attachments.forEach((attachment, index) => {
    if (!acceptedTypes.has(attachment?.type)) {
      throw error(
        'ATTACHMENT_TYPE_UNSUPPORTED',
        `attachments[${index}] type is not supported by this provider`,
        415,
      )
    }
    const lifecycle = inferredLifecycle(attachment)
    if (!supportedLifecycles.has(lifecycle)) {
      throw error(
        'ATTACHMENT_LIFECYCLE_UNSUPPORTED',
        `attachments[${index}] lifecycle is not supported by this provider`,
        415,
      )
    }
  })
}
