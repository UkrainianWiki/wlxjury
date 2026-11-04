// The compatibility lint of our stylesheets (docs/ui/practices.md §6.1a): every CSS feature
// main.css and theme.css use must be supported by the browsers of the repository's
// .browserslistrc (stylelint-no-unsupported-browser-features, from caniuse via doiuse). Only
// this rule: it is a compatibility check, not a style guide. Bootstrap and the other webjars are
// not ours; unite-gallery.css and ug-theme-default.css are the vendored gallery's (UI §7).
export default {
  plugins: ['stylelint-no-unsupported-browser-features'],
  rules: {
    'plugin/no-unsupported-browser-features': [true, { severity: 'error' }],
  },
};
