import test from 'node:test';
import assert from 'node:assert/strict';

function createField(name, typeName) {
  return {
    getName() {
      return name;
    },
    setAccessible() {},
    getType() {
      return {
        getName() {
          return typeName;
        },
      };
    },
    get(instance) {
      return instance.__values[name] ?? null;
    },
    set(instance, value) {
      instance.__values[name] = value;
    },
  };
}

function createClass(name, fieldTypes) {
  const fields = new Map(
    Object.entries(fieldTypes).map(([fieldName, typeName]) => [fieldName, createField(fieldName, typeName)]),
  );

  return {
    getName() {
      return name;
    },
    getDeclaredField(fieldName) {
      const field = fields.get(fieldName);
      if (field == null) {
        throw new Error(`missing field ${name}.${fieldName}`);
      }
      return field;
    },
    getDeclaredFields() {
      return [...fields.values()];
    },
  };
}

function createInstance(klass, values, stringValue) {
  return {
    __values: { ...values },
    getClass() {
      return klass;
    },
    toString() {
      return stringValue;
    },
  };
}

test('rewriteAttachmentText prefers the field matching the serialized attachment text', async () => {
  const reflection = await import('../shared/chatlog-reflection.js');

  const attachmentClass = createClass('fake.Attachment', {
    shadow: 'java.lang.String',
    payload: 'java.lang.String',
  });
  const serialized = '{"url":"file:///tmp/iris-graft-tok123-0.jpg","callingPkg":"party.qwer.iris"}';
  const attachment = createInstance(
    attachmentClass,
    {
      shadow: 'callingPkg shadow metadata',
      payload: serialized,
    },
    serialized,
  );
  const sendingLogClass = createClass('fake.ChatSendingLog', {
    G: 'fake.Attachment',
  });
  const sendingLog = createInstance(
    sendingLogClass,
    {
      G: attachment,
    },
    '[sendingLog]',
  );

  assert.equal(
    reflection.rewriteAttachmentText(
      sendingLog,
      'G',
      '{"url":"file:///tmp/iris-graft-tok123-0.jpg"}',
    ),
    true,
  );
  assert.equal(attachment.__values.payload, '{"url":"file:///tmp/iris-graft-tok123-0.jpg"}');
  assert.equal(attachment.__values.shadow, 'callingPkg shadow metadata');
});

test('readChatRoomId coerces values through the cached java.lang.String helper', async () => {
  const reflection = await import('../shared/chatlog-reflection.js');

  const javaBridge = {
    useCalls: [],
    use(className) {
      this.useCalls.push(className);
      if (className !== 'java.lang.String') {
        throw new Error(`unexpected class ${className}`);
      }
      return {
        valueOf(value) {
          return String(value);
        },
      };
    },
  };
  const sendingLog = {
    getChatRoomId() {
      return 18476130232878491n;
    },
  };

  assert.equal(reflection.readChatRoomId(javaBridge, sendingLog), '18476130232878491');
  assert.equal(reflection.readChatRoomId(javaBridge, sendingLog), '18476130232878491');
  assert.deepEqual(javaBridge.useCalls, ['java.lang.String']);
});
