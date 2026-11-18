#!/bin/bash
# Un cloud WebDAV come quello della Storage server di il server (Apache + mod_dav, password Basic), in un contenitore,
# su 127.0.0.1:<porta> (l'emulatore lo vede come 10.0.2.2). Solo dati di prova.
# Registro: richiesta, esito, Range chiesto, byte DAVVERO usciti sul cavo (%O: conta anche le letture interrotte).
# Uso: apache-dav.sh start <cartella-dati> <porta> <password> [KiB/s per collegamento, come i dati mobili] | stop <porta>
set -e
cmd=$1
if [ "$cmd" = stop ]; then docker rm -f "st-apache-$2" >/dev/null 2>&1 || true; exit 0; fi
DATA=$(realpath "$2"); PORT=$3; PASS=$4; RATE=${5:-}
CONF=$(mktemp -d /tmp/st-apache-conf.XXXX)
cat > "$CONF/httpd.conf" <<CONFX
ServerRoot "/usr/local/apache2"
Listen 127.0.0.1:$PORT
LoadModule mpm_event_module modules/mod_mpm_event.so
LoadModule authn_file_module modules/mod_authn_file.so
LoadModule authn_core_module modules/mod_authn_core.so
LoadModule authz_user_module modules/mod_authz_user.so
LoadModule authz_core_module modules/mod_authz_core.so
LoadModule auth_basic_module modules/mod_auth_basic.so
LoadModule dav_module modules/mod_dav.so
LoadModule dav_fs_module modules/mod_dav_fs.so
LoadModule dav_lock_module modules/mod_dav_lock.so
LoadModule mime_module modules/mod_mime.so
LoadModule dir_module modules/mod_dir.so
LoadModule unixd_module modules/mod_unixd.so
LoadModule log_config_module modules/mod_log_config.so
$( [ -n "$RATE" ] && printf "LoadModule ratelimit_module modules/mod_ratelimit.so\nLoadModule env_module modules/mod_env.so" )
LoadModule logio_module modules/mod_logio.so
User daemon
Group daemon
ServerName localhost
DocumentRoot "/data"
DavLockDB "/tmp/davlock"
TypesConfig conf/mime.types
ErrorLog /proc/self/fd/2
LogFormat "%r %>s \"%{Range}i\" %O" s
CustomLog /proc/self/fd/1 s
<Directory "/data">
  Dav On
  AuthType Basic
  AuthName "WebDAV Restricted"
  AuthUserFile /conf/htpasswd
  Require valid-user
$( [ -n "$RATE" ] && printf '  SetOutputFilter RATE_LIMIT\n  SetEnv rate-limit %s\n' "$RATE" )
  Options None
  AllowOverride None
</Directory>
CONFX
docker run --rm --entrypoint htpasswd httpd:2.4 -nbB prova "$PASS" > "$CONF/htpasswd"
chmod -R a+rwX "$DATA"; chmod 755 "$CONF"; chmod 644 "$CONF"/*
docker rm -f "st-apache-$PORT" >/dev/null 2>&1 || true
# rete dell'host (solo 127.0.0.1): con un VPN acceso (Mullvad) la rete dei contenitori non risponde, il loopback sì
docker run -d --name "st-apache-$PORT" --network host -v "$DATA:/data" -v "$CONF:/conf:ro" httpd:2.4 httpd-foreground -f /conf/httpd.conf >/dev/null
sleep 2
