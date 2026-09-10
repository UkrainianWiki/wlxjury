# httpd:2.4 runs the master process as root to bind port 80; worker processes drop to www-data.
# Non-root hardening requires NET_BIND_SERVICE capability, PID file relocation, and port remapping —
# out of scope for this image; the trade-off is intentional.
FROM httpd:2.4

RUN sed -i \
    -e 's/#LoadModule proxy_module/LoadModule proxy_module/' \
    -e 's/#LoadModule proxy_http_module/LoadModule proxy_http_module/' \
    -e 's/#LoadModule rewrite_module/LoadModule rewrite_module/' \
    -e 's/#LoadModule headers_module/LoadModule headers_module/' \
    -e 's/#LoadModule ssl_module/LoadModule ssl_module/' \
    /usr/local/apache2/conf/httpd.conf \
 && echo 'Include /usr/local/apache2/conf/extra/jury-images.conf' \
    >> /usr/local/apache2/conf/httpd.conf \
 && echo 'ServerName localhost' >> /usr/local/apache2/conf/httpd.conf
