// Microsoft account sign-in for Minecraft: Microsoft OAuth (in a small window, with PKCE) ->
// Xbox Live -> XSTS -> Minecraft services. Only the Microsoft refresh token is kept on disk; the
// Minecraft token is fetched fresh at every Play.
const crypto = require('crypto');
const { BrowserWindow } = require('electron');

const REDIRECT = 'https://login.microsoftonline.com/common/oauth2/nativeclient';
const SCOPE = 'XboxLive.signin offline_access';
const b64url = (b) => b.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

class AuthError extends Error {
  constructor(message, code) {
    super(message);
    this.code = code;
  }
}

async function post(url, body, form) {
  const r = await fetch(url, {
    method: 'POST',
    headers: form ? { 'Content-Type': 'application/x-www-form-urlencoded' } : { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: form ? new URLSearchParams(body).toString() : JSON.stringify(body),
  });
  const t = await r.text();
  let j = {};
  try {
    j = JSON.parse(t);
  } catch {}
  if (!r.ok) {
    const e = new AuthError(j.error_description || j.errorMessage || j.Message || `Sign-in step failed (${r.status})`, j.XErr || j.error || r.status);
    e.status = r.status;
    e.body = j;
    throw e;
  }
  return j;
}

function window(parent, clientId) {
  return new Promise((resolve, reject) => {
    const verifier = b64url(crypto.randomBytes(32));
    const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
    const url =
      'https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?' +
      new URLSearchParams({
        client_id: clientId,
        response_type: 'code',
        redirect_uri: REDIRECT,
        scope: SCOPE,
        prompt: 'select_account',
        code_challenge: challenge,
        code_challenge_method: 'S256',
      }).toString();
    const win = new BrowserWindow({
      width: 500,
      height: 680,
      parent,
      modal: !!parent,
      autoHideMenuBar: true,
      title: 'Sign in with Microsoft',
      backgroundColor: '#0b0e0d',
      webPreferences: { partition: 'persist:msauth', sandbox: true },
    });
    let done = false;
    const check = (u) => {
      if (!u || !u.startsWith(REDIRECT)) return;
      done = true;
      const q = new URL(u).searchParams;
      win.close();
      if (q.get('code')) resolve({ code: q.get('code'), verifier });
      else reject(new AuthError(q.get('error_description') || 'Sign-in was cancelled.', q.get('error') || 'cancelled'));
    };
    win.webContents.on('will-redirect', (_e, u) => check(u));
    win.webContents.on('will-navigate', (_e, u) => check(u));
    win.webContents.on('did-navigate', (_e, u) => check(u));
    win.on('closed', () => {
      if (!done) reject(new AuthError('Sign-in was cancelled.', 'cancelled'));
    });
    win.loadURL(url);
  });
}

async function minecraft(msAccess) {
  const xbl = await post('https://user.auth.xboxlive.com/user/authenticate', {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: 'd=' + msAccess },
    RelyingParty: 'http://auth.xboxlive.com',
    TokenType: 'JWT',
  });
  const uhs = xbl.DisplayClaims.xui[0].uhs;
  let xsts;
  try {
    xsts = await post('https://xsts.auth.xboxlive.com/xsts/authorize', {
      Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.Token] },
      RelyingParty: 'rp://api.minecraftservices.com/',
      TokenType: 'JWT',
    });
  } catch (e) {
    const x = e.body && e.body.XErr;
    if (x === 2148916233) throw new AuthError('This Microsoft account has no Xbox profile yet. Sign in once at minecraft.net, then try again.', 'noxbox');
    if (x === 2148916238) throw new AuthError('This is a child account: an adult has to add it to a Microsoft family first.', 'child');
    if (x === 2148916235) throw new AuthError('Xbox Live is not available in your country.', 'region');
    throw e;
  }
  let mc;
  try {
    mc = await post('https://api.minecraftservices.com/authentication/login_with_xbox', { identityToken: `XBL3.0 x=${uhs};${xsts.Token}` });
  } catch (e) {
    if (e.status === 403) throw new AuthError('Minecraft refused this launcher (its Microsoft app ID is not approved for Minecraft yet).', 'appid');
    throw e;
  }
  const r = await fetch('https://api.minecraftservices.com/minecraft/profile', { headers: { Authorization: 'Bearer ' + mc.access_token } });
  if (r.status === 404) throw new AuthError("This account doesn't own Minecraft: Java Edition.", 'noprofile');
  if (!r.ok) throw new AuthError(`Could not read your Minecraft profile (${r.status}).`, r.status);
  const p = await r.json();
  return { name: p.name, uuid: p.id, access: mc.access_token, xuid: xsts.DisplayClaims.xui[0].xid || '' };
}

/** First sign-in: opens the Microsoft window. Returns the account (with refresh token) and a live session. */
async function login(parent, clientId) {
  if (!clientId || clientId === 'SET_ME') throw new AuthError('This build of the launcher has no Microsoft app ID set.', 'noclient');
  const { code, verifier } = await window(parent, clientId);
  const t = await post(
    'https://login.microsoftonline.com/consumers/oauth2/v2.0/token',
    { client_id: clientId, grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier, scope: SCOPE },
    true
  );
  const s = await minecraft(t.access_token);
  return { account: { name: s.name, uuid: s.uuid, xuid: s.xuid, refresh: t.refresh_token }, session: s };
}

/** Later: a fresh session from the saved refresh token (the refresh token rotates). */
async function refresh(account, clientId) {
  const t = await post(
    'https://login.microsoftonline.com/consumers/oauth2/v2.0/token',
    { client_id: clientId, grant_type: 'refresh_token', refresh_token: account.refresh, scope: SCOPE, redirect_uri: REDIRECT },
    true
  );
  const s = await minecraft(t.access_token);
  return { account: { name: s.name, uuid: s.uuid, xuid: s.xuid, refresh: t.refresh_token || account.refresh }, session: s };
}

module.exports = { login, refresh, AuthError };
