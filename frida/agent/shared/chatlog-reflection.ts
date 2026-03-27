type JavaBridgeLike = {
  use: (className: string) => unknown;
};

type JavaClassLike = {
  getName?: () => string;
  getDeclaredField: (fieldName: string) => ReflectFieldLike;
  getDeclaredFields?: () => ReflectFieldLike[];
};

type ReflectFieldLike = {
  setAccessible: (flag: boolean) => void;
  getType: () => { getName: () => string };
  get: (target: unknown) => unknown;
  set: (target: unknown, value: unknown) => void;
};

type JavaObjectLike = {
  getClass: () => JavaClassLike;
};

const stringClassCache = new WeakMap<object, { valueOf: (value: unknown) => string }>();
const attachmentFieldCache = new Map<string, ReflectFieldLike>();
const attachmentTextFieldCache = new Map<string, ReflectFieldLike>();

function getClassName(klass: JavaClassLike): string | null {
  try {
    const name = klass.getName?.();
    return typeof name === 'string' && name.length > 0 ? name : null;
  } catch {
    return null;
  }
}

function getAttachmentField(sendingLog: JavaObjectLike, attachmentFieldName: string): ReflectFieldLike | null {
  const klass = sendingLog.getClass();
  const className = getClassName(klass);
  const cacheKey = className == null ? null : `${className}:${attachmentFieldName}`;
  if (cacheKey != null) {
    const cached = attachmentFieldCache.get(cacheKey);
    if (cached != null) {
      return cached;
    }
  }

  try {
    const field = klass.getDeclaredField(attachmentFieldName);
    field.setAccessible(true);
    if (cacheKey != null) {
      attachmentFieldCache.set(cacheKey, field);
    }
    return field;
  } catch {
    return null;
  }
}

function getAttachmentObject(sendingLog: unknown, attachmentFieldName: string): unknown | null {
  if (sendingLog == null || typeof sendingLog !== 'object' || !('getClass' in sendingLog)) {
    return null;
  }

  const field = getAttachmentField(sendingLog as JavaObjectLike, attachmentFieldName);
  if (field == null) {
    return null;
  }

  try {
    return field.get(sendingLog) ?? null;
  } catch {
    return null;
  }
}

function resolveStringClass(javaBridge: JavaBridgeLike): { valueOf: (value: unknown) => string } | null {
  const cached = stringClassCache.get(javaBridge as object);
  if (cached != null) {
    return cached;
  }

  try {
    const StringClass = javaBridge.use('java.lang.String') as { valueOf: (value: unknown) => string };
    stringClassCache.set(javaBridge as object, StringClass);
    return StringClass;
  } catch {
    return null;
  }
}

function resolveAttachmentTextField(attachment: unknown): ReflectFieldLike | null {
  if (attachment == null || typeof attachment !== 'object' || !('getClass' in attachment)) {
    return null;
  }

  const attachmentObject = attachment as JavaObjectLike;
  const attachmentClass = attachmentObject.getClass();
  const className = getClassName(attachmentClass);
  if (className != null) {
    const cached = attachmentTextFieldCache.get(className);
    if (cached != null) {
      return cached;
    }
  }

  const serialized = String(attachment);
  let fallback: ReflectFieldLike | null = null;

  try {
    for (const field of attachmentClass.getDeclaredFields?.() ?? []) {
      field.setAccessible(true);
      if (field.getType().getName() !== 'java.lang.String') {
        continue;
      }

      const value = field.get(attachment);
      if (value == null) {
        continue;
      }

      const text = String(value);
      if (text === serialized) {
        if (className != null) {
          attachmentTextFieldCache.set(className, field);
        }
        return field;
      }

      if (fallback == null && text.includes('callingPkg')) {
        fallback = field;
      }
    }
  } catch {
    return null;
  }

  if (className != null && fallback != null) {
    attachmentTextFieldCache.set(className, fallback);
  }
  return fallback;
}

export function readChatRoomId(javaBridge: JavaBridgeLike | null | undefined, sendingLog: { getChatRoomId: () => unknown }): string | null {
  if (javaBridge == null) {
    return null;
  }

  try {
    const StringClass = resolveStringClass(javaBridge);
    if (StringClass == null) {
      return null;
    }

    return StringClass.valueOf(sendingLog.getChatRoomId());
  } catch {
    return null;
  }
}

export function readAttachmentText(sendingLog: unknown, attachmentFieldName: string): string | null {
  const attachment = getAttachmentObject(sendingLog, attachmentFieldName);
  return attachment == null ? null : String(attachment);
}

export function rewriteAttachmentText(sendingLog: unknown, attachmentFieldName: string, newText: string): boolean {
  const attachment = getAttachmentObject(sendingLog, attachmentFieldName);
  if (attachment == null) {
    return false;
  }

  const payloadField = resolveAttachmentTextField(attachment);
  if (payloadField == null) {
    return false;
  }

  try {
    payloadField.set(attachment, newText);
    return true;
  } catch {
    return false;
  }
}
