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
  if (!attachmentText.includes('callingPkg')) {
    return attachmentText;
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
  if (!Object.hasOwn(payload, 'callingPkg')) {
    return attachmentText;
  }

  delete payload.callingPkg;
  return JSON.stringify(payload);
}

const MAX_ATTACHMENT_CANDIDATE_VISITS = 50_000;

function collectStringCandidates(value: unknown, out: string[]): void {
  const stack: unknown[] = [value];
  const seen = new WeakSet<object>();
  let visits = 0;

  while (stack.length > 0 && visits < MAX_ATTACHMENT_CANDIDATE_VISITS) {
    const current = stack.pop();
    visits += 1;

    if (typeof current === 'string') {
      if (current.includes('iris-graft-')) {
        out.push(current);
      }
      continue;
    }

    if (Array.isArray(current)) {
      for (let index = current.length - 1; index >= 0; index -= 1) {
        stack.push(current[index]);
      }
      continue;
    }

    if (current == null || typeof current !== 'object') {
      continue;
    }

    if (seen.has(current)) {
      continue;
    }
    seen.add(current);

    const children = Object.values(current as Record<string, unknown>);
    for (let index = children.length - 1; index >= 0; index -= 1) {
      stack.push(children[index]);
    }
  }
}

export function extractPathCandidatesFromAttachment(attachmentText: string | null | undefined): string[] {
  if (attachmentText == null) {
    return [];
  }
  if (!attachmentText.includes('iris-graft-')) {
    return [];
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(attachmentText);
  } catch {
    return [attachmentText];
  }

  const candidates: string[] = [];
  collectStringCandidates(parsed, candidates);
  return candidates;
}
