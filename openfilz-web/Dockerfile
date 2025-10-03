FROM nginxinc/nginx-unprivileged:alpine-perl
COPY dist/openfilz-ui/browser /usr/share/nginx/html
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

USER nginx

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]