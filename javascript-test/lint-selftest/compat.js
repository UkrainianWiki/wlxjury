// The compatibility lint's self-test (npm run lint:selftest): APIs that some supported browsers
// lack, which the lint must report. Not served: outside public/.
var pattern = new URLPattern({ pathname: '/large/*' });
var sorted = [3, 1, 2].toSorted();
window.wlxLintSelftest = [pattern, sorted];
