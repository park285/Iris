📦
11490 /thread-image-graft.js
✄
// shared/hint.ts
function parseThreadHintJson(raw) {
  const parsed = JSON.parse(raw);
  if (typeof parsed.token !== "string" || typeof parsed.room !== "string" || typeof parsed.threadId !== "string" || typeof parsed.threadScope !== "number" || typeof parsed.createdAt !== "number") {
    throw new Error("invalid thread hint");
  }
  return {
    token: parsed.token,
    room: parsed.room,
    threadId: parsed.threadId,
    threadScope: parsed.threadScope,
    createdAt: parsed.createdAt
  };
}
function extractTokenFromImageName(pathOrName) {
  const match = /iris-graft-([A-Za-z0-9-]+)-\d+\.[A-Za-z0-9]+$/.exec(pathOrName);
  return match?.[1] ?? null;
}
function buildPendingHintPath(root, token) {
  return `${root}/pending/${token}.json`;
}
function buildClaimedHintPath(root, token) {
  return `${root}/claimed/${token}.json`;
}
function buildDoneHintPath(root, token) {
  return `${root}/done/${token}.json`;
}
function planHintClaim(root, token) {
  return {
    token,
    pendingPath: buildPendingHintPath(root, token),
    claimedPath: buildClaimedHintPath(root, token),
    donePath: buildDoneHintPath(root, token)
  };
}

// shared/kakao.ts
var KAKAO_IMAGE_GRAFT_TARGET = {
  className: "com.kakao.talk.manager.send.ChatSendingLogRequest$a",
  methodName: "u",
  attachmentField: "G",
  threadScopeField: "Z",
  threadIdField: "V0"
};

// shared/message.ts
function isPhotoMessageType(messageType) {
  return messageType === "Photo";
}
function roomMatchesThreadHint(roomId, hintRoom) {
  return roomId === hintRoom;
}
function removeCallingPkgFromAttachment(attachmentText) {
  if (attachmentText == null) {
    return null;
  }
  let parsed;
  try {
    parsed = JSON.parse(attachmentText);
  } catch {
    return attachmentText;
  }
  if (parsed == null || Array.isArray(parsed) || typeof parsed !== "object") {
    return attachmentText;
  }
  const payload = { ...parsed };
  if (!Object.prototype.hasOwnProperty.call(payload, "callingPkg")) {
    return attachmentText;
  }
  delete payload.callingPkg;
  return JSON.stringify(payload);
}
function collectStringCandidates(value, out) {
  if (typeof value === "string") {
    if (value.includes("iris-graft-")) {
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
  if (value != null && typeof value === "object") {
    for (const child of Object.values(value)) {
      collectStringCandidates(child, out);
    }
  }
}
function extractPathCandidatesFromAttachment(attachmentText) {
  if (attachmentText == null) {
    return [];
  }
  let parsed;
  try {
    parsed = JSON.parse(attachmentText);
  } catch {
    return attachmentText.includes("iris-graft-") ? [attachmentText] : [];
  }
  const candidates = [];
  collectStringCandidates(parsed, candidates);
  return candidates;
}

// thread-image-graft.ts
var DEFAULT_HINT_ROOT = "/data/local/tmp/iris-thread-hints";
function extractTokenFromCandidates(pathCandidates) {
  for (const candidate of pathCandidates) {
    if (candidate == null) {
      continue;
    }
    const token = extractTokenFromImageName(candidate);
    if (token !== null) {
      return token;
    }
  }
  return null;
}
function mapClaimFailureStatus(kind) {
  switch (kind) {
    case "missing":
      return "skip-claim-missing";
    case "invalid":
      return "skip-claim-invalid";
    case "claim-failed":
      return "skip-claim-failed";
  }
}
function planImageInjection(input) {
  if (!isPhotoMessageType(input.messageType)) {
    return {
      status: "skip-non-photo",
      token: null,
      cleanedAttachment: input.attachmentText ?? null,
      claim: null,
      injection: null
    };
  }
  const cleanedAttachment = removeCallingPkgFromAttachment(input.attachmentText);
  const token = extractTokenFromCandidates(input.pathCandidates);
  if (token === null) {
    return {
      status: "skip-missing-token",
      token: null,
      cleanedAttachment,
      claim: null,
      injection: null
    };
  }
  if (input.claimResult === null) {
    return {
      status: "skip-claim-missing",
      token,
      cleanedAttachment,
      claim: null,
      injection: null
    };
  }
  const claim = input.claimResult.claim;
  if (input.claimResult.kind === "claimed") {
    if (input.claimResult.hint.token !== token) {
      return {
        status: "skip-token-mismatch",
        token,
        cleanedAttachment,
        claim,
        injection: null
      };
    }
    if (!roomMatchesThreadHint(input.roomId, input.claimResult.hint.room)) {
      return {
        status: "skip-room-mismatch",
        token,
        cleanedAttachment,
        claim,
        injection: null
      };
    }
    return {
      status: "inject",
      token,
      cleanedAttachment,
      claim,
      injection: {
        threadId: input.claimResult.hint.threadId,
        threadScope: input.claimResult.hint.threadScope
      }
    };
  }
  if (input.claimResult.token !== token) {
    return {
      status: "skip-token-mismatch",
      token,
      cleanedAttachment,
      claim,
      injection: null
    };
  }
  return {
    status: mapClaimFailureStatus(input.claimResult.kind),
    token,
    cleanedAttachment,
    claim,
    injection: null
  };
}
function getThreadImageGraftEntrypoint() {
  return {
    target: KAKAO_IMAGE_GRAFT_TARGET,
    planImageInjection
  };
}
function log(runtime, message) {
  runtime.send?.(message);
}
function readAttachmentText(sendingLog) {
  try {
    const cls = sendingLog.getClass();
    const attachmentField = cls.getDeclaredField(KAKAO_IMAGE_GRAFT_TARGET.attachmentField);
    attachmentField.setAccessible(true);
    const attachment = attachmentField.get(sendingLog);
    if (attachment == null) {
      return null;
    }
    return attachment.toString();
  } catch {
    return null;
  }
}
function rewriteAttachmentText(sendingLog, newText) {
  const cls = sendingLog.getClass();
  const attachmentField = cls.getDeclaredField(KAKAO_IMAGE_GRAFT_TARGET.attachmentField);
  attachmentField.setAccessible(true);
  const attachment = attachmentField.get(sendingLog);
  if (attachment == null) {
    return;
  }
  const attachmentClass = attachment.getClass();
  const fields = attachmentClass.getDeclaredFields();
  for (const field of fields) {
    field.setAccessible(true);
    if (field.getType().getName() === "java.lang.String") {
      const value = field.get(attachment);
      if (value != null && value.toString().includes("callingPkg")) {
        field.set(attachment, newText);
        return;
      }
    }
  }
}
function collectPathCandidates(sendingLog, attachmentText) {
  const candidates = attachmentText == null ? [] : extractPathCandidatesFromAttachment(attachmentText);
  if (candidates.length > 0) {
    return candidates;
  }
  try {
    const fallback = sendingLog.t?.()?.toString?.();
    return extractPathCandidatesFromAttachment(fallback ?? null);
  } catch {
    return candidates;
  }
}
function readRoomId(runtime, sendingLog) {
  try {
    const JavaBridge = runtime.Java;
    return JavaBridge.use("java.lang.String").valueOf(sendingLog.getChatRoomId());
  } catch {
    return null;
  }
}
function readMessageType(sendingLog) {
  try {
    return sendingLog.w0().toString();
  } catch {
    return null;
  }
}
function claimThreadHint(runtime, root, token) {
  const claim = planHintClaim(root, token);
  const JavaBridge = runtime.Java;
  const File = JavaBridge.use("java.io.File");
  const Scanner = JavaBridge.use("java.util.Scanner");
  const pendingFile = File.$new(claim.pendingPath);
  if (!pendingFile.exists()) {
    return { kind: "missing", token, claim };
  }
  const claimedFile = File.$new(claim.claimedPath);
  claimedFile.getParentFile()?.mkdirs?.();
  if (!pendingFile.renameTo(claimedFile)) {
    return { kind: "claim-failed", token, claim };
  }
  try {
    const scanner = Scanner.$new(claimedFile);
    const content = scanner.useDelimiter("\\A").next();
    scanner.close();
    return {
      kind: "claimed",
      hint: parseThreadHintJson(content),
      claim
    };
  } catch {
    return { kind: "invalid", token, claim };
  }
}
function finalizeClaim(runtime, claim) {
  if (claim == null) {
    return;
  }
  try {
    const JavaBridge = runtime.Java;
    const File = JavaBridge.use("java.io.File");
    const claimedFile = File.$new(claim.claimedPath);
    if (!claimedFile.exists()) {
      return;
    }
    const doneFile = File.$new(claim.donePath);
    doneFile.getParentFile()?.mkdirs?.();
    if (!claimedFile.renameTo(doneFile) && claimedFile.exists()) {
      claimedFile.delete();
    }
  } catch {
  }
}
function applyInjection(runtime, sendingLog, plan) {
  if (plan.cleanedAttachment != null && plan.cleanedAttachment !== readAttachmentText(sendingLog)) {
    try {
      rewriteAttachmentText(sendingLog, plan.cleanedAttachment);
      log(runtime, `[graft] cleaned callingPkg token=${plan.token ?? "none"}`);
    } catch {
      log(runtime, `[graft] failed to rewrite attachment token=${plan.token ?? "none"}`);
    }
  }
  if (plan.status !== "inject" || plan.injection == null) {
    finalizeClaim(runtime, plan.claim);
    log(runtime, `[graft] skip status=${plan.status} token=${plan.token ?? "none"}`);
    return;
  }
  try {
    const JavaBridge = runtime.Java;
    const cls = sendingLog.getClass();
    const scopeField = cls.getDeclaredField(KAKAO_IMAGE_GRAFT_TARGET.threadScopeField);
    scopeField.setAccessible(true);
    scopeField.setInt(sendingLog, plan.injection.threadScope);
    const threadIdField = cls.getDeclaredField(KAKAO_IMAGE_GRAFT_TARGET.threadIdField);
    threadIdField.setAccessible(true);
    threadIdField.set(sendingLog, JavaBridge.use("java.lang.Long").valueOf(JavaBridge.use("java.lang.String").valueOf(plan.injection.threadId)));
    log(runtime, `[graft] injected token=${plan.token ?? "none"} threadId=${plan.injection.threadId} scope=${plan.injection.threadScope}`);
  } finally {
    finalizeClaim(runtime, plan.claim);
  }
}
function installThreadImageGraftHook(runtime = globalThis, hintRoot = DEFAULT_HINT_ROOT) {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null || typeof JavaBridge.perform !== "function") {
    return false;
  }
  JavaBridge.perform(() => {
    const RequestCompanion = JavaBridge.use(KAKAO_IMAGE_GRAFT_TARGET.className);
    const overloads = RequestCompanion[KAKAO_IMAGE_GRAFT_TARGET.methodName].overloads;
    for (const overload of overloads) {
      overload.implementation = function(...args) {
        const sendingLog = args[1];
        const attachmentText = readAttachmentText(sendingLog);
        const pathCandidates = collectPathCandidates(sendingLog, attachmentText);
        const token = extractTokenFromCandidates(pathCandidates);
        const claimResult = token == null ? null : claimThreadHint(runtime, hintRoot, token);
        const plan = planImageInjection({
          messageType: readMessageType(sendingLog),
          roomId: readRoomId(runtime, sendingLog),
          attachmentText,
          pathCandidates,
          claimResult
        });
        applyInjection(runtime, sendingLog, plan);
        return overload.apply(this, args);
      };
    }
    log(runtime, `[graft] thread-image-graft hook installed root=${hintRoot}`);
  });
  return true;
}
if (globalThis.Java?.available) {
  installThreadImageGraftHook();
}
export {
  getThreadImageGraftEntrypoint,
  installThreadImageGraftHook,
  planImageInjection
};
