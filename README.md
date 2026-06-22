# CardIA

App Android : détecte la présence d'une carte à collectionner dans le champ de la caméra (vision LLM OpenAI) et l'affiche par un indicateur.

Cible : Android 14+ (minSdk 29). Kotlin + Jetpack Compose.

## Architecture (AOO-38)

L'app **n'embarque aucune clé IA**. C'est le **serveur** (extension de l'API
TCGPricer) qui détient la clé OpenAI et appelle la vision. Chaque installation
s'authentifie avec sa **propre clé de licence**, obtenue via un **code
d'invitation** émis par l'administrateur.

Flux :
1. L'admin émet un code : `POST /api/cardia/invite` (header `x-cardia-admin-token`).
2. Dans les **Réglages** de l'app : saisir l'**URL du serveur** (ex.
   `https://<host>/api/cardia`) + le **code d'invitation**, puis **Activer**.
   L'app appelle `POST {url}/register` et stocke la clé de licence (chiffrée).
3. Détection : `POST {url}/detect`, `Authorization: Bearer <licence>`,
   corps `{image:{kind:"base64",mediaType,data}}` → `{card: true|false}`.
   Quota journalier appliqué par clé (429 si dépassé).

Côté serveur : voir `tcgpricer/src/cardia_vision.js` + routes `/api/cardia/*`
dans `tcgpricer/src/server.js` ; tables `cardia_invite`, `cardia_install`,
`cardia_usage_daily` dans `tcgpricer/src/db.js`. Secret : `CARDIA_ADMIN_TOKEN`
(réutilise `OPENAI_API_KEY`).

## Build
```bash
source /opt/datas/tools/android-env.sh
./gradlew assembleDebug
```

## Suivi
Jira projet **AOO**, épic **AOO-34** (CardIA).
