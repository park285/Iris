import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const testDir = path.dirname(__filename);
const repoRoot = path.resolve(testDir, '..', '..', '..');
const fridaRoot = path.join(repoRoot, 'frida');
const legacyRoot = path.join(fridaRoot, 'legacy');
const agentRoot = path.join(fridaRoot, 'agent');

function listJsFiles(rootDir) {
  const entries = fs.readdirSync(rootDir, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    const fullPath = path.join(rootDir, entry.name);
    if (entry.isDirectory()) {
      files.push(...listJsFiles(fullPath));
      continue;
    }
    if (entry.isFile() && fullPath.endsWith('.js')) {
      files.push(path.relative(repoRoot, fullPath));
    }
  }

  return files.sort();
}

test('frida task 1 layout preserves legacy assets and locks toolchain boundaries', () => {
  assert.equal(
    fs.existsSync(path.join(legacyRoot, 'thread-image-graft.js')),
    true,
    'expected legacy thread-image-graft.js under frida/legacy',
  );
  assert.equal(
    fs.existsSync(path.join(legacyRoot, 'thread-markdown-graft.js')),
    true,
    'expected legacy thread-markdown-graft.js under frida/legacy',
  );
  assert.equal(
    fs.existsSync(path.join(legacyRoot, 'graft-daemon.py')),
    true,
    'expected legacy graft-daemon.py under frida/legacy',
  );
  assert.equal(
    fs.existsSync(path.join(agentRoot, 'package.json')),
    true,
    'expected frida/agent/package.json to exist',
  );
  assert.equal(
    fs.existsSync(path.join(agentRoot, 'tsconfig.json')),
    true,
    'expected frida/agent/tsconfig.json to exist',
  );

  const jsOutsideLegacy = listJsFiles(fridaRoot).filter(
    (relativePath) =>
      !relativePath.startsWith('frida/legacy/') &&
      !relativePath.startsWith('frida/agent/generated/') &&
      !relativePath.startsWith('frida/agent/node_modules/'),
  );
  assert.deepEqual(
    jsOutsideLegacy,
    [],
    `expected no .js source outside frida/legacy, found: ${jsOutsideLegacy.join(', ')}`,
  );
});
