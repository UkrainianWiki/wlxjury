// The compatibility lint's self-test (npm run lint:selftest): the lint must fail on the fixtures
// here, each finding named, or it checks nothing (testing practices §4.2). Run from
// javascript-test/.
import { spawnSync } from 'node:child_process';

const ROOT = '..';
// the tools' own scripts, run by this Node: no shell, the same on Windows and Linux
const tool = (script, args) => {
  const r = spawnSync(process.execPath, [`javascript-test/node_modules/${script}`, ...args], { cwd: ROOT, encoding: 'utf8' });
  return { status: r.status, output: `${r.stdout}${r.stderr}` };
};

const checks = [
  ['eslint-plugin-compat',
    tool('eslint/bin/eslint.js', ['-c', 'javascript-test/eslint.config.mjs', 'javascript-test/lint-selftest/compat.js']),
    [/URLPattern is not supported/, /Array\.toSorted\(\) is not supported in Chrome 109/]],
  ['stylelint-no-unsupported-browser-features',
    tool('stylelint/bin/stylelint.mjs', ['--config', 'javascript-test/stylelint.config.mjs', 'javascript-test/lint-selftest/compat.css']),
    [/"css-has" is not supported by Firefox 115/]],
];

let failed = 0;
for (const [name, { status, output }, expected] of checks) {
  const missing = expected.filter((p) => !p.test(output));
  if (status === 0 || missing.length) {
    failed++;
    console.error(`[lint-selftest] ${name}: expected it to fail naming ${missing.join(', ')}; exit ${status}\n${output}`);
  } else {
    console.log(`[lint-selftest] ${name}: fails on its fixture, as it should`);
  }
}
process.exit(failed ? 1 : 0);
