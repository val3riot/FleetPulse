#!/bin/sh
set -eu

: "${FLEET_API_UPSTREAM:=http://fleet-api:8080}"
invalid() {
    echo 'FLEET_API_UPSTREAM non valido: usare http://host[:porta], senza path o credenziali.' >&2
    exit 1
}
# Reject newlines and Nginx syntax before substituting the only configurable variable.
case "$FLEET_API_UPSTREAM" in
    *[!a-zA-Z0-9_.:/-]*) invalid ;;
esac
printf '%s\n' "$FLEET_API_UPSTREAM" | grep -Eq '^http://[a-zA-Z0-9][a-zA-Z0-9_.-]*(:[0-9]{1,5})?$' || invalid
host_port=${FLEET_API_UPSTREAM#http://}
case "$host_port" in
    *:*) port=${host_port##*:}; [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || invalid ;;
esac
export FLEET_API_UPSTREAM
runtime_dir=$(mktemp -d /tmp/fleetpulse-nginx.XXXXXX)
envsubst '${FLEET_API_UPSTREAM}' < /etc/fleetpulse/nginx.conf.template > "$runtime_dir/nginx.conf"
nginx -t -c "$runtime_dir/nginx.conf"
exec nginx -c "$runtime_dir/nginx.conf" -g 'daemon off;'
