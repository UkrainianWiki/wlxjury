// The compatibility lint of our page scripts (docs/ui/practices.md §6.1a; testing §10): every
// browser API they use must exist in the supported browsers, the repository's .browserslistrc
// (eslint-plugin-compat, from MDN's browser-compat-data); and their syntax must parse as ES2022,
// what Chrome 109 and Firefox 115 run. Only this rule: it is a compatibility check, not a style
// guide. Run from the repository's root: npm --prefix javascript-test run lint.
import compat from 'eslint-plugin-compat';
import globals from 'globals';

export default [
  {
    // the vendored, patched gallery and jQuery plugins (UI practices §7): upstream code we don't
    // rewrite; the browser tests run them in all three engines
    ignores: [
      'public/javascripts/unitegallery.js',
      'public/javascripts/ug-theme-tiles.js',
      'public/javascripts/jquery.hotkeys.js',
      'public/javascripts/jquery.raty.js',
    ],
  },
  {
    files: ['public/javascripts/*.js'],
    plugins: { compat },
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: { ...globals.browser, module: 'readonly' },
    },
    linterOptions: { reportUnusedDisableDirectives: 'error' },
    rules: { 'compat/compat': 'error' },
  },
];
