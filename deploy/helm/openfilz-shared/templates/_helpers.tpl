{{- define "openfilz-shared.labels" -}}
app.kubernetes.io/part-of: openfilz-shared
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "openfilz-shared.keycloakSecretName" -}}
{{- if .Values.keycloak.existingSecret }}{{ .Values.keycloak.existingSecret }}{{ else }}{{ .Release.Name }}-keycloak{{ end }}
{{- end }}

{{/* nginx configuration of the OnlyOffice router, one server line per replica. */}}
{{- define "openfilz-shared.onlyofficeRouterConf" -}}
worker_processes auto;
pid /tmp/nginx.pid;
events { worker_connections 4096; }
http {
  log_format routed '$remote_addr "$request" $status shard=$arg_shardkey upstream=$upstream_addr';
  access_log /dev/stdout routed;
  error_log /dev/stderr warn;
  client_body_temp_path /tmp/client;
  proxy_temp_path /tmp/proxy;
  fastcgi_temp_path /tmp/fastcgi;
  uwsgi_temp_path /tmp/uwsgi;
  scgi_temp_path /tmp/scgi;
  resolver __RESOLVER__ valid=10s;
  map $http_upgrade $connection_upgrade { default upgrade; '' close; }
  map $http_x_forwarded_host $forwarded_host { default $http_x_forwarded_host; '' $host; }
  map $http_x_forwarded_proto $forwarded_proto { default $http_x_forwarded_proto; '' $scheme; }
  upstream documentserver {
    zone documentserver 1m;
    hash $arg_shardkey consistent;
    {{- range $i := until (int .Values.onlyoffice.replicas) }}
    server {{ $.Release.Name }}-onlyoffice-{{ $i }}.{{ $.Release.Name }}-onlyoffice-pods.{{ $.Release.Namespace }}.svc.cluster.local:80 resolve;
    {{- end }}
  }
  server {
    listen 8080;
    client_max_body_size 0;
    location = /router-health { access_log off; default_type text/plain; return 200 "ok"; }
    location / {
      proxy_pass http://documentserver;
      # A server that is gone or still starting: the next one takes the document, without a long wait.
      proxy_connect_timeout 3s;
      proxy_next_upstream error timeout http_502 http_503 http_504;
      proxy_next_upstream_tries 2;
      proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection $connection_upgrade;
      proxy_set_header Host $host;
      proxy_set_header X-Forwarded-Host $forwarded_host;
      proxy_set_header X-Forwarded-Proto $forwarded_proto;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_read_timeout 3600s;
      proxy_send_timeout 3600s;
      proxy_buffering off;
      proxy_request_buffering off;
    }
  }
}
{{- end }}

{{- define "openfilz-shared.onlyofficeSecretName" -}}
{{- if .Values.onlyoffice.existingSecret }}{{ .Values.onlyoffice.existingSecret }}{{ else }}{{ .Release.Name }}-onlyoffice{{ end }}
{{- end }}
