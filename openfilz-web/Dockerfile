FROM nginx:alpine
COPY dist/openfilz-ui/browser /usr/share/nginx/html
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

RUN  chgrp -R 0 /usr/share/nginx && chmod -R g=u /usr/share/nginx
RUN  chgrp -R 0 /var/cache/nginx && chmod -R g=u /var/cache/nginx
RUN  chgrp -R 0 /etc/nginx && chmod -R g=u /etc/nginx

USER nginx

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]