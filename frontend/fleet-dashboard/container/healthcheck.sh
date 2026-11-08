#!/bin/sh
set -eu
[ -s /usr/share/nginx/html/index.html ]
wget -q --spider -T 2 http://127.0.0.1:8080/healthz
