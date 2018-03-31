#!/bin/bash
cd "$(dirname "$0")"
echo $1"<br>" >> ./guestbook.txt
printf "HTTP/1.1 200 OK\r\n"
printf "Content-type: text/html\r\n"
printf "\r\n"
printf "<html>\n"
printf "<body>\n"
printf "<a href="\\index.html">Go Home</a><br>\n"
printf "<h1 align="left">Guest Book</h1>\n"
cat ./guestbook.txt
printf "</body>\n"
printf "</html>"