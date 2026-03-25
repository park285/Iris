📦
2867 /thread-markdown-graft.js
✄
// shared/kakao.ts
var KAKAO_MARKDOWN_GRAFT_TARGET = {
  className: "com.kakao.talk.manager.send.ChatSendingLogRequest$a",
  methodName: "u",
  threadScopeField: "Z",
  threadIdField: "V0"
};

// thread-markdown-graft.ts
var DEFAULT_MARKDOWN_GRAFT_MARKER = "FRIDA_GRAFT";
function planMarkdownInjection(messageText, marker, threadId, threadScope) {
  if (messageText == null || !messageText.includes(marker)) {
    return {
      status: "skip-no-marker",
      injection: null
    };
  }
  return {
    status: "inject",
    injection: {
      threadId,
      threadScope
    }
  };
}
function getThreadMarkdownGraftEntrypoint() {
  return {
    target: KAKAO_MARKDOWN_GRAFT_TARGET,
    marker: DEFAULT_MARKDOWN_GRAFT_MARKER,
    planMarkdownInjection
  };
}
function log(runtime, message) {
  runtime.send?.(message);
}
function readMessageText(sendingLog) {
  try {
    return sendingLog.f0().toString();
  } catch {
    return null;
  }
}
function installThreadMarkdownGraftHook(runtime = globalThis, marker = DEFAULT_MARKDOWN_GRAFT_MARKER, threadId = "3803466729815130113", threadScope = 2) {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null || typeof JavaBridge.perform !== "function") {
    return false;
  }
  JavaBridge.perform(() => {
    const RequestCompanion = JavaBridge.use(KAKAO_MARKDOWN_GRAFT_TARGET.className);
    const overloads = RequestCompanion[KAKAO_MARKDOWN_GRAFT_TARGET.methodName].overloads;
    for (const overload of overloads) {
      overload.implementation = function(...args) {
        const sendingLog = args[1];
        const plan = planMarkdownInjection(readMessageText(sendingLog), marker, threadId, threadScope);
        if (plan.status === "inject" && plan.injection != null) {
          const cls = sendingLog.getClass();
          const scopeField = cls.getDeclaredField(KAKAO_MARKDOWN_GRAFT_TARGET.threadScopeField);
          scopeField.setAccessible(true);
          scopeField.setInt(sendingLog, plan.injection.threadScope);
          const threadField = cls.getDeclaredField(KAKAO_MARKDOWN_GRAFT_TARGET.threadIdField);
          threadField.setAccessible(true);
          threadField.set(sendingLog, JavaBridge.use("java.lang.Long").valueOf(JavaBridge.use("java.lang.String").valueOf(plan.injection.threadId)));
          log(runtime, `[graft] markdown injected threadId=${plan.injection.threadId} scope=${plan.injection.threadScope}`);
        } else {
          log(runtime, `[graft] markdown skip status=${plan.status}`);
        }
        return overload.apply(this, args);
      };
    }
    log(runtime, `[graft] thread-markdown-graft hook installed marker=${marker}`);
  });
  return true;
}
if (globalThis.Java?.available) {
  installThreadMarkdownGraftHook();
}
export {
  DEFAULT_MARKDOWN_GRAFT_MARKER,
  getThreadMarkdownGraftEntrypoint,
  installThreadMarkdownGraftHook,
  planMarkdownInjection
};
