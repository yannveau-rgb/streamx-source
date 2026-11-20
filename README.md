# StreamX

Client IPTV pour Android TV / Google TV (Kotlin, Jetpack Compose), avec un onglet « Tube » (vidéos en ligne sans compte).
IPTV client for Android TV / Google TV (Kotlin, Jetpack Compose), with a "Tube" tab (online videos, no account).

**FR** — Ce dépôt n'existe que pour respecter la licence des bibliothèques libres utilisées (GPLv3). Il est publié tel quel :
aucun support, aucune demande de modification acceptée, les tickets sont désactivés. StreamX ne fournit, n'héberge et ne recommande aucun contenu ni aucun abonnement :
il lit seulement ce que l'utilisateur configure lui-même. Pas affilié à YouTube, Google, TMDB ni à un fournisseur IPTV.

**EN** — This repository exists only to comply with the licence of the free libraries used (GPLv3). It is published as is:
no support, no contributions accepted, issues are disabled. StreamX provides, hosts and recommends no content or subscription:
it only plays what the user configures. Not affiliated with YouTube, Google, TMDB or any IPTV provider.

## Licence
GNU General Public License v3.0 — voir / see `LICENSE`. L'onglet Tube utilise [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPLv3).
Les données de tendances viennent de TMDB : ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.

## Compilation
JDK 17 et Android SDK 35 ; `gradlew assembleDebug`. Fichiers facultatifs hors dépôt, dans `~/streamx-keys/` :
`tmdb.properties` (`key=...`) pour les tendances TMDB, `keystore.properties` pour signer une version de publication.
