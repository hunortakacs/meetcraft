#!/usr/bin/env sh
# Signs the Firefox extension as an unlisted (self-distributed) add-on on addons.mozilla.org and
# drops the installable .xpi in dist/. Needs your AMO API credentials in WEB_EXT_API_KEY and
# WEB_EXT_API_SECRET (https://addons.mozilla.org/developers/addon/api/key/), either in the
# environment or in a git-ignored .env at the repo root; web-ext reads them from the environment,
# so they never end up on the command line.
# Signs extension/ (Meet) by default; pass reels-extension to sign the Instagram one.
# Bump "version" in its manifest.json first: AMO won't sign the same version twice.
set -eu
dir="${1:-extension}"
cd "$(dirname "$0")/.."
if [ -f .env ]; then
	set -a
	. ./.env
	set +a
fi
: "${WEB_EXT_API_KEY:?set WEB_EXT_API_KEY to your AMO JWT issuer}"
: "${WEB_EXT_API_SECRET:?set WEB_EXT_API_SECRET to your AMO JWT secret}"
npx --yes web-ext@8 sign --channel=unlisted --source-dir "$dir" --artifacts-dir dist
