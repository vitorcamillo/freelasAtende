#!/usr/bin/env node
// Gera um GMAIL_REFRESH_TOKEN com escopo gmail.readonly para o login autonomo
// (leitura do OTP). Uso:
//
//   GMAIL_OAUTH_CLIENT_ID=... GMAIL_OAUTH_CLIENT_SECRET=... \
//     node scripts/gmail-oauth-refresh-token.mjs
//
// O client OAuth deve ser do tipo "Desktop app" (ou "Web" com o redirect
// http://localhost:<porta> autorizado). O script abre um servidor local
// temporario, imprime a URL de consentimento, recebe o code e troca por tokens.
//
// Nada e gravado em disco: o refresh token e apenas impresso para voce colocar
// no ambiente do servidor (Secret Manager / systemd / docker), nunca no git.

import http from "node:http";
import { randomBytes } from "node:crypto";

const clientId = process.env.GMAIL_OAUTH_CLIENT_ID;
const clientSecret = process.env.GMAIL_OAUTH_CLIENT_SECRET;
const port = Number(process.env.OAUTH_LOCAL_PORT || "53682");
const scope = "https://www.googleapis.com/auth/gmail.readonly";

if (!clientId || !clientSecret) {
  console.error("Defina GMAIL_OAUTH_CLIENT_ID e GMAIL_OAUTH_CLIENT_SECRET no ambiente.");
  process.exit(1);
}

const redirectUri = `http://localhost:${port}`;
const state = randomBytes(16).toString("hex");

const authUrl = new URL("https://accounts.google.com/o/oauth2/v2/auth");
authUrl.searchParams.set("client_id", clientId);
authUrl.searchParams.set("redirect_uri", redirectUri);
authUrl.searchParams.set("response_type", "code");
authUrl.searchParams.set("scope", scope);
authUrl.searchParams.set("access_type", "offline");
authUrl.searchParams.set("prompt", "consent");
authUrl.searchParams.set("state", state);

console.log("\n1) Abra esta URL no navegador logado na conta do OTP:\n");
console.log(authUrl.toString());
console.log("\n2) Aprove o acesso somente-leitura. O token aparece aqui.\n");

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, redirectUri);
  if (!url.searchParams.get("code")) {
    res.writeHead(400).end("Sem code.");
    return;
  }
  if (url.searchParams.get("state") !== state) {
    res.writeHead(400).end("State invalido.");
    return;
  }
  const code = url.searchParams.get("code");
  try {
    const body = new URLSearchParams({
      code,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: redirectUri,
      grant_type: "authorization_code"
    });
    const resp = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body
    });
    const json = await resp.json();
    if (!json.refresh_token) {
      res.writeHead(500).end("Sem refresh_token. Revogue o acesso e tente de novo com prompt=consent.");
      console.error("Resposta sem refresh_token:", JSON.stringify(json));
      server.close();
      process.exit(1);
    }
    res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" })
       .end("Refresh token gerado. Pode fechar esta aba e voltar ao terminal.");
    console.log("=== COLOQUE NO AMBIENTE DO SERVIDOR (nunca no git) ===\n");
    console.log(`GMAIL_OAUTH_CLIENT_ID=${clientId}`);
    console.log(`GMAIL_OAUTH_CLIENT_SECRET=${clientSecret}`);
    console.log(`GMAIL_REFRESH_TOKEN=${json.refresh_token}`);
    console.log(`GMAIL_USER_ID=me`);
    console.log("\nPronto: o login assistido passa a ler o OTP do e-mail sozinho.");
  } catch (e) {
    res.writeHead(500).end("Erro na troca do code: " + e.message);
    console.error(e);
  } finally {
    server.close();
  }
});

server.listen(port, () => {
  console.log(`Aguardando o retorno do Google em ${redirectUri} ...`);
});
