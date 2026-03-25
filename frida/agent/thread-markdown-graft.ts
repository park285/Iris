import { KAKAO_MARKDOWN_GRAFT_TARGET } from './shared/kakao.js';

export const DEFAULT_MARKDOWN_GRAFT_MARKER = 'FRIDA_GRAFT';

type RuntimeLike = {
  Java?: any;
  send?: (message: string) => void;
};

export type MarkdownInjectionPlan = {
  status: 'skip-no-marker' | 'inject';
  injection: {
    threadId: string;
    threadScope: number;
  } | null;
};

export function planMarkdownInjection(
  messageText: string | null | undefined,
  marker: string,
  threadId: string,
  threadScope: number,
): MarkdownInjectionPlan {
  if (messageText == null || !messageText.includes(marker)) {
    return {
      status: 'skip-no-marker',
      injection: null,
    };
  }

  return {
    status: 'inject',
    injection: {
      threadId,
      threadScope,
    },
  };
}

export function getThreadMarkdownGraftEntrypoint() {
  return {
    target: KAKAO_MARKDOWN_GRAFT_TARGET,
    marker: DEFAULT_MARKDOWN_GRAFT_MARKER,
    planMarkdownInjection,
  };
}

function log(runtime: RuntimeLike, message: string): void {
  runtime.send?.(message);
}

function readMessageText(sendingLog: any): string | null {
  try {
    return sendingLog.f0().toString();
  } catch {
    return null;
  }
}

export function installThreadMarkdownGraftHook(
  runtime: RuntimeLike = globalThis as RuntimeLike,
  marker = DEFAULT_MARKDOWN_GRAFT_MARKER,
  threadId = '3803466729815130113',
  threadScope = 2,
): boolean {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null || typeof JavaBridge.perform !== 'function') {
    return false;
  }

  JavaBridge.perform(() => {
    const RequestCompanion = JavaBridge.use(KAKAO_MARKDOWN_GRAFT_TARGET.className);
    const overloads = RequestCompanion[KAKAO_MARKDOWN_GRAFT_TARGET.methodName].overloads;
    for (const overload of overloads) {
      overload.implementation = function (...args: any[]) {
        const sendingLog = args[1];
        const plan = planMarkdownInjection(readMessageText(sendingLog), marker, threadId, threadScope);

        if (plan.status === 'inject' && plan.injection != null) {
          const cls = sendingLog.getClass();

          const scopeField = cls.getDeclaredField(KAKAO_MARKDOWN_GRAFT_TARGET.threadScopeField);
          scopeField.setAccessible(true);
          scopeField.setInt(sendingLog, plan.injection.threadScope);

          const threadField = cls.getDeclaredField(KAKAO_MARKDOWN_GRAFT_TARGET.threadIdField);
          threadField.setAccessible(true);
          threadField.set(
            sendingLog,
            JavaBridge.use('java.lang.Long').valueOf(
              JavaBridge.use('java.lang.String').valueOf(plan.injection.threadId),
            ),
          );

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

if ((globalThis as RuntimeLike).Java?.available) {
  installThreadMarkdownGraftHook();
}
