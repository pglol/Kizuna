// Release name and notes for the version in package.json.
// Patches (0.11, 0.12…) get their own notes; an update (0.2, 0.3…) gets every patch since the last update.
// Usage: node scripts/notes.js name|notes
const pkg = require('../package.json');
const log = require('../CHANGELOG.json');

const parse = (v) => (/^(\d+)\.(\d+)\.(\d+)/.exec(v) || []).slice(1).map(Number);
const shown = (v) => {
  const [maj, min] = parse(v);
  if (maj !== 0) return v;
  return min % 10 === 0 ? `0.${min / 10}` : `0.${String(min).padStart(2, '0')}`;
};

const [maj, min] = parse(pkg.version);
const isUpdate = maj === 0 && min % 10 === 0;
if (process.argv[2] === 'name') {
  console.log(`Kizuna Launcher ${shown(pkg.version)}`);
} else if (!isUpdate) {
  console.log((log[pkg.version] || []).map((x) => '- ' + x).join('\n'));
} else {
  // This update, then each patch since the previous one, newest first.
  const parts = [];
  const own = log[pkg.version] || [];
  if (own.length) parts.push(own.map((x) => '- ' + x).join('\n'));
  const patches = Object.keys(log)
    .filter((k) => {
      const [a, b] = parse(k);
      return a === 0 && b > min - 10 && b < min;
    })
    .sort((x, y) => parse(y)[1] - parse(x)[1]);
  for (const k of patches) parts.push(`**${shown(k)}**\n` + log[k].map((x) => '- ' + x).join('\n'));
  console.log(parts.join('\n\n'));
}
