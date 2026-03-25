export const KAKAO_IMAGE_GRAFT_TARGET = {
  className: 'com.kakao.talk.manager.send.ChatSendingLogRequest$a',
  methodName: 'u',
  attachmentField: 'G',
  threadScopeField: 'Z',
  threadIdField: 'V0',
} as const;

export const KAKAO_MARKDOWN_GRAFT_TARGET = {
  className: 'com.kakao.talk.manager.send.ChatSendingLogRequest$a',
  methodName: 'u',
  threadScopeField: 'Z',
  threadIdField: 'V0',
} as const;

export const BASE_INTENT_FILTER_TARGET = {
  className: 'com.kakao.talk.activity.f',
  captureMethod: 'b6',
  bridgeMethod: 'Z5',
} as const;

export const CHAT_MEDIA_SENDER_TARGET = {
  className: 'bh.c',
  entryMethod: 'o',
  processMethod: 't',
  injectMethod: 'A',
} as const;

export const THREAD_IMAGE_GRAFT_HOOK_SPECS = {
  b6: {
    className: BASE_INTENT_FILTER_TARGET.className,
    methodName: BASE_INTENT_FILTER_TARGET.captureMethod,
  },
  o: {
    className: CHAT_MEDIA_SENDER_TARGET.className,
    methodName: CHAT_MEDIA_SENDER_TARGET.entryMethod,
  },
  t: {
    className: CHAT_MEDIA_SENDER_TARGET.className,
    methodName: CHAT_MEDIA_SENDER_TARGET.processMethod,
  },
  A: {
    className: CHAT_MEDIA_SENDER_TARGET.className,
    methodName: CHAT_MEDIA_SENDER_TARGET.injectMethod,
  },
  u: {
    className: KAKAO_IMAGE_GRAFT_TARGET.className,
    methodName: KAKAO_IMAGE_GRAFT_TARGET.methodName,
  },
} as const;

export type ThreadImageGraftHookKey = keyof typeof THREAD_IMAGE_GRAFT_HOOK_SPECS;
