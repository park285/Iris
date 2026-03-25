export type ThreadHint = {
  token: string;
  room: string;
  threadId: string;
  threadScope: number;
  createdAt: number;
};

export type HintClaimPlan = {
  token: string;
  pendingPath: string;
  claimedPath: string;
  donePath: string;
};

export function parseThreadHintJson(raw: string): ThreadHint {
  const parsed = JSON.parse(raw) as Partial<ThreadHint>;
  if (
    typeof parsed.token !== 'string' ||
    typeof parsed.room !== 'string' ||
    typeof parsed.threadId !== 'string' ||
    typeof parsed.threadScope !== 'number' ||
    typeof parsed.createdAt !== 'number'
  ) {
    throw new Error('invalid thread hint');
  }
  return {
    token: parsed.token,
    room: parsed.room,
    threadId: parsed.threadId,
    threadScope: parsed.threadScope,
    createdAt: parsed.createdAt,
  };
}

export function encodeThreadHint(hint: ThreadHint): string {
  return JSON.stringify(hint);
}

export function extractTokenFromImageName(pathOrName: string): string | null {
  const match = /iris-graft-([A-Za-z0-9-]+)-\d+\.[A-Za-z0-9]+$/.exec(pathOrName);
  return match?.[1] ?? null;
}

export function buildPendingHintPath(root: string, token: string): string {
  return `${root}/pending/${token}.json`;
}

export function buildClaimedHintPath(root: string, token: string): string {
  return `${root}/claimed/${token}.json`;
}

export function buildDoneHintPath(root: string, token: string): string {
  return `${root}/done/${token}.json`;
}

export function planHintClaim(root: string, token: string): HintClaimPlan {
  return {
    token,
    pendingPath: buildPendingHintPath(root, token),
    claimedPath: buildClaimedHintPath(root, token),
    donePath: buildDoneHintPath(root, token),
  };
}
