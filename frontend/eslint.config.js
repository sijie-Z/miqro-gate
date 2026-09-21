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
  {
    // The handbook renders the repository's own docs/user-guide markdown,
    // inlined at build time (#869) — a trusted compile-time input rather than
    // user content, so the XSS heuristic does not apply. Revisit with a
    // sanitizer if the source ever stops being our own repo files.
    name: 'app/help-renders-trusted-docs',
    files: ['src/views/next/NextHelpView.vue'],
    rules: {
      'vue/no-v-html': 'off',
    },
  },
];
