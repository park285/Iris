export function isPhotoMessageType(messageType: string | null | undefined): boolean {
  return messageType === 'Photo';
}

export function roomMatchesThreadHint(roomId: string | null | undefined, hintRoom: string): boolean {
  return roomId === hintRoom;
}

export function removeCallingPkgFromAttachment(attachmentText: string | null | undefined): string | null {
  if (attachmentText == null) {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(attachmentText);
  } catch {
    return attachmentText;
  }

  if (parsed == null || Array.isArray(parsed) || typeof parsed !== 'object') {
    return attachmentText;
  }

  const payload = { ...(parsed as Record<string, unknown>) };
  if (!Object.prototype.hasOwnProperty.call(payload, 'callingPkg')) {
    return attachmentText;
  }

  delete payload.callingPkg;
  return JSON.stringify(payload);
}

function collectStringCandidates(value: unknown, out: string[]): void {
  if (typeof value === 'string') {
    if (value.includes('iris-graft-')) {
      out.push(value);
    }
    return;
  }

  if (Array.isArray(value)) {
    for (const item of value) {
      collectStringCandidates(item, out);
    }
    return;
  }

  if (value != null && typeof value === 'object') {
    for (const child of Object.values(value as Record<string, unknown>)) {
      collectStringCandidates(child, out);
    }
  }
}

export function extractPathCandidatesFromAttachment(attachmentText: string | null | undefined): string[] {
  if (attachmentText == null) {
    return [];
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(attachmentText);
  } catch {
    return attachmentText.includes('iris-graft-') ? [attachmentText] : [];
  }

  const candidates: string[] = [];
  collectStringCandidates(parsed, candidates);
  return candidates;
}
