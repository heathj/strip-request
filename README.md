# strip-request

A tool for stripping away unnecessary headers, query parameters, and post body 
parameters from an HTTP request.

## Installation

Download one of the release [jars](https://github.com/heathj/strip-request/releases)
and use it:

`java -jar strip-request.jar -h`

## Usage

`strip-request` is simple to use with raw HTTP requests that are usually acquired
from the browser or through a tool such as Burp repeater. A normal workflow, for
exmaple, when using Repeater would be right-click a request in Repeater and select,
"Copy to File...". Once the request is saved, strip the request using the following
command:

`java -jar strip-request.jar -i request.txt`

The following request to [exmaple.com](http://example.com)

~~~http
GET / HTTP/1.1
Host: example.com
User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/60.0
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Language: en-US,en;q=0.5
Accept-Encoding: gzip, deflate
Connection: keep-alive
Upgrade-Insecure-Requests: 1
Pragma: no-cache
Cache-Control: no-cache

~~~

will produce the following output

~~~
Original request:
-----------------
GET / HTTP/1.1
Host: example.com
User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/60.0
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Language: en-US,en;q=0.5
Accept-Encoding: gzip, deflate
Connection: keep-alive
Upgrade-Insecure-Requests: 1
Pragma: no-cache
Cache-Control: no-cache



Base response:
-----------------
{:headers
 {"Vary" "Accept-Encoding",
  "X-Cache" "HIT",
  "Last-Modified" "Fri, 09 Aug 2013 235435 GMT",
  "Date" "Sat, 30 Jun 2018 193225 GMT",
  "Accept-Ranges" "bytes",
  "Cache-Control" "max-age=604800",
  "Content-Length" "606",
  "Server" "ECS (sjc/4E38)",
  "Content-Type" "text/html",
  "Content-Encoding" "gzip",
  "Etag" "\"1541025663+gzip\"",
  "Expires" "Sat, 07 Jul 2018 193225 GMT"},
 :content-length 606,
 :status-code 200,
 :status-msg "OK"}

Striped request:
-----------------
GET / HTTP/1.1
Accept-Encoding: gzip, deflate
Host: example.com



Stripped response:
-----------------
{:headers
 {"Vary" "Accept-Encoding",
  "X-Cache" "HIT",
  "Last-Modified" "Fri, 09 Aug 2013 235435 GMT",
  "Date" "Sat, 30 Jun 2018 193225 GMT",
  "Accept-Ranges" "bytes",
  "Cache-Control" "max-age=604800",
  "Content-Length" "606",
  "Server" "ECS (sjc/4E39)",
  "Content-Type" "text/html",
  "Content-Encoding" "gzip",
  "Etag" "\"1541025663+gzip\"",
  "Expires" "Sat, 07 Jul 2018 193225 GMT"},
 :content-length 606,
:status-code 200,
 :status-msg "OK"}
~~~

## Options

~~~
strip-request: a command line tool for stripping away unnecessary headers, query parameters, and cookies from requests.

Usage: strip-request [options]

Options:
  -u, --http                  Make the request over HTTP (defaults to TLS).
  -t, --host HOST  127.0.0.1  Host to make the request to
  -p, --port PORT  443        Port number to connect to.
  -r, --req FILE              File containing an HTTP request.
  -v                          Verbose mode; more v's -> more verbose.
  -h, --help
~~~
