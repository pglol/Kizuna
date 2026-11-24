// Run by CI before building: puts the Microsoft app ID and server-list URL (from repo secrets and
// variables) into config.json, and takes the app's name from config.json, the one place to rename it.
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const cfgFile = path.join(root, 'config.json');
const pkgFile = path.join(root, 'package.json');
const c = JSON.parse(fs.readFileSync(cfgFile, 'utf8'));
if (process.env.CLIENT_ID) c.msClientId = process.env.CLIENT_ID;
if (process.env.HUB_URL) c.hubUrl = process.env.HUB_URL;
// Test builds (not published) can try the whole install path without a Microsoft sign-in.
c.testMode = process.env.TEST_MODE === 'true';
fs.writeFileSync(cfgFile, JSON.stringify(c, null, 2));
const p = JSON.parse(fs.readFileSync(pkgFile, 'utf8'));
p.productName = p.build.productName = c.name + ' Launcher';
fs.writeFileSync(pkgFile, JSON.stringify(p, null, 2));
console.log('name:', p.productName, '| client id set:', c.msClientId !== 'SET_ME', '| hub:', c.hubUrl, '| test build:', c.testMode);
