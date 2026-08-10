#!/usr/bin/env bash
# install-hermes.sh: put this repository's plugins where Hermes will find them.
#
# History
#   2026-08-07  A. Sigdel  Created.
#
# Usage
#   scripts/install-hermes.sh [--copy] [--uninstall]
#
# Two plugins live here and neither had any way of reaching Hermes. The provider
# profile's own docstring says to symlink it into $HERMES_HOME/plugins, and
# bootstrap-pi.sh closes with a banner listing that as a manual step, which is
# to say it did not happen. $HERMES_HOME/plugins did not exist at all, so the
# profile that points Hermes at the router had never loaded, and `provider:
# neuralwatt` would have failed to resolve even if somebody had set it.
#
# This installs. It does not enable: a user plugin loads only when its name is
# in `plugins.enabled`, and the profile matters only once `model.provider` names
# it, both of which live in the configuration. So after this runs the plugins
# are present and inert, and running it against a working Hermes changes nothing
# about how that Hermes behaves. Applying the configuration is a separate script
# for a separate reason: the live config is hand-tuned and merging into it
# safely is its own problem.
#
# Idempotent by readlink-compare, as install-zeromem.sh already is for its own
# symlink: a link already pointing where it should is reported and left alone.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
readonly HERMES_HOME
readonly PLUGINS="$HERMES_HOME/plugins"

# Each entry is "source-under-hermes/|destination-under-plugins/".
#
# The provider profile sits in a category directory because that is where Hermes
# discovers profiles; the middleware is a flat plugin and sits at the top. Their
# depth differs, so the pairs are written out rather than derived from a glob.
readonly INSTALLS=(
    "plugins/model-providers/neuralwatt|model-providers/neuralwatt"
    "plugins/session_routing|session_routing"
)

mode=symlink
action=install
for arg in "$@"; do
    case "$arg" in
        # For the systemd deployment: hermes.service runs as user `hermes` under
        # ProtectSystem=strict, and a copy removes any dependency on where this
        # checkout lives or who can read it. Symlinks are the default because a
        # developer editing a plugin should not have to reinstall it.
        --copy) mode=copy ;;
        --uninstall) action=uninstall ;;
        *)
            printf 'usage: %s [--copy] [--uninstall]\n' "$0" >&2
            exit 2
            ;;
    esac
done
readonly mode action

step() { printf '\n== %s ==\n' "$1"; }
ok() { printf '   ok    %s\n' "$1"; }
did() { printf '   done  %s\n' "$1"; }
bad() { printf '   FAIL  %s\n' "$1" >&2; }

failed=0

step "hermes home"
if [ -d "$HERMES_HOME" ]; then
    ok "$HERMES_HOME"
else
    # Not created. An absent Hermes home means Hermes is not installed here, and
    # scattering plugin directories into a path it will never read is worse than
    # saying so.
    bad "$HERMES_HOME does not exist; is Hermes installed?"
    printf '   Set HERMES_HOME if it lives somewhere else.\n'
    exit 1
fi

step "plugins"
for entry in "${INSTALLS[@]}"; do
    source_rel="${entry%%|*}"
    dest_rel="${entry##*|}"
    target="$ROOT/hermes/$source_rel"
    link="$PLUGINS/$dest_rel"

    if [ ! -d "$target" ]; then
        bad "missing in this repository: hermes/$source_rel"
        failed=1
        continue
    fi

    if [ "$action" = uninstall ]; then
        if [ -L "$link" ] || [ -d "$link" ]; then
            rm -rf "$link" && did "removed $dest_rel"
        else
            ok "$dest_rel is not installed"
        fi
        continue
    fi

    mkdir -p "$(dirname "$link")"

    if [ "$mode" = copy ]; then
        # Compared rather than replaced blindly, so a re-run is quiet when
        # nothing moved and loud when something did.
        if [ -d "$link" ] && [ ! -L "$link" ] && diff -rq "$target" "$link" >/dev/null 2>&1; then
            ok "$dest_rel is current"
        else
            rm -rf "$link"
            cp -R "$target" "$link" && did "copied $dest_rel"
        fi
        continue
    fi

    if [ "$(readlink "$link" 2>/dev/null)" = "$target" ]; then
        ok "$dest_rel"
    else
        # -n so that relinking an existing symlink-to-a-directory replaces the
        # link rather than dropping a second one inside what it points at.
        ln -sfn "$target" "$link" && did "linked $dest_rel"
    fi
done

if [ "$action" = uninstall ]; then
    step "done"
    printf '   The plugins are gone. Anything naming them in plugins.enabled or\n'
    printf '   model.provider will now fail to resolve; unset those too.\n'
    exit "$failed"
fi

step "what is not done"
# Said plainly, because the gap between "installed" and "in use" is exactly
# where this stack has been sitting: the profile existed in the repository for
# days while Hermes talked to somebody else entirely.
printf '   Installed, and inert. Nothing loads until the configuration says so:\n'
printf '     plugins.enabled       must list session_routing\n'
printf '     model.provider        must be neuralwatt\n'
printf '     model.base_url        must be the router\n'
printf '   hermes/config.yaml holds the intended values.\n'

exit "$failed"
