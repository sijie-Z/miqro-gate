import pluginVue from 'eslint-plugin-vue';
import vueTsEslintConfig from '@vue/eslint-config-typescript';
import prettierConfig from '@vue/eslint-config-prettier';

export default [
  {
    name: 'app/files-to-lint',
    files: ['**/*.{ts,tsx,vue}'],
  },
  {
    name: 'app/files-to-ignore',
    // generated.ts is machine output ("do not make direct changes") whose shape is
    // decided by openapi-typescript. Linting it made two CI steps contradict each
    // other: the codegen drift check passes against the committed form, then lint
    // reformats the same file and nothing looks (#822).
    ignores: ['**/dist/**', '**/dist-ssr/**', '**/coverage/**', 'src/types/generated.ts'],
  },
  ...pluginVue.configs['flat/recommended'],
  ...vueTsEslintConfig(),
  prettierConfig,
  {
    // Test files define stub components inline to drive a router, a slot or a
    // child; the rule's intent is one component per shipped file, so it does not
    // apply in these scopes.
    name: 'app/tests-allow-inline-components',
    files: ['src/__tests__/**', 'e2e/**'],
    rules: {
      'vue/one-component-per-file': 'off',
    },
  },
  {
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },
];
