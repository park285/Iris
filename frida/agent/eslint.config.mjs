import js from '@eslint/js';
import tseslint from 'typescript-eslint';

const fridaInteropBoundaryFiles = [
  'thread-image-discovery.ts',
  'thread-image-graft.ts',
  'thread-markdown-graft.ts',
  'test/thread-image-health.test.ts',
];

export default tseslint.config(
  {
    ignores: ['**/generated/**', '**/node_modules/**'],
    linterOptions: {
      reportUnusedDisableDirectives: 'error',
    },
  },
  {
    files: ['**/*.mjs'],
    extends: [js.configs.recommended],
  },
  {
    files: ['**/*.ts'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    rules: {
      '@typescript-eslint/ban-ts-comment': [
        'error',
        {
          minimumDescriptionLength: 10,
          'ts-check': false,
          'ts-expect-error': true,
          'ts-ignore': true,
          'ts-nocheck': true,
        },
      ],
      '@typescript-eslint/no-unused-vars': 'off',
      '@typescript-eslint/no-require-imports': 'error',
      '@typescript-eslint/no-explicit-any': [
        'error',
        {
          fixToUnknown: false,
          ignoreRestArgs: false,
        },
      ],
      'no-useless-concat': 'error',
      'no-var': 'error',
      'object-shorthand': ['error', 'always'],
      'prefer-arrow-callback': [
        'error',
        {
          allowNamedFunctions: false,
          allowUnboundThis: false,
        },
      ],
      'prefer-const': [
        'error',
        {
          destructuring: 'all',
        },
      ],
      'prefer-object-has-own': 'error',
      'prefer-rest-params': 'error',
      'prefer-spread': 'error',
      'prefer-template': 'error',
    },
  },
  {
    files: fridaInteropBoundaryFiles,
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      'prefer-arrow-callback': 'off',
    },
  },
);
