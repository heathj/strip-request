(ns strip-request.core
  (:require [clojure.string :as string]
            [clojure.data.json :as json]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io])
  (:import (java.net Socket InetAddress InetSocketAddress)
           (javax.net.ssl SSLSocketFactory X509TrustManager SSLContext)
           (java.security KeyStore)
           (java.io OutputStreamWriter InputStreamReader PushbackReader))
  (:gen-class))

(def cli-options
  "Parse the command line arguments to this program"
  [["-u" "--http" "Make the request over HTTP (defaults to TLS)."
    :default false]
   ["-t" "--host HOST" "Host to make the request to"
    :default "127.0.0.1"
    :parse-fn #(.getHostAddress (InetAddress/getByName %))]
   ["-p" "--port PORT" "Port number to connect to."
    :default 443
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Port number must be between 0 and 65536"]]
   ["-r" "--req FILE" "File containing an HTTP request."
    :default ""
    :parse-fn #(slurp %)]
   ["-v" nil "Verbose mode; more v's -> more verbose."
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ["-h" "--help"]])

(defn match-on
  "Given two request maps, decide if they match based on one of their attributes"
  [base new attr]
  (= (attr base) (attr new)))

(defn match
  "Given two request maps, decide if they match."
  [base new]
  (and (match-on base new :content-length)
       (match-on base new :status-code)
       (match-on base new :status-msg)))

(defn usage [options-summary]
  (->> ["strip-request: a command line tool for stripping away unnecessary headers, query parameters, and cookies from requests."
        ""
        "Usage: strip-request [options]"
        ""
        "Options:"
        options-summary
        ""]
       (string/join \newline)))

(defn error-msg 
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-opts
  "Validate all the command line arguments make sense"
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
     (:help options)
     {:exit-message (usage summary) :ok? true}
     errors
     {:exit-message (error-msg errors)}
     (empty? (:request options))
     {:exit-message (str (error-msg ["Need to specify a request to send.\n\n"]) (usage summary))}
     :else
     {:opts options})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(def test-get-request "GET / HTTP/1.1
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Encoding: gzip, deflate
Accept-Language: en-US,en;q=0.5
Cache-Control: no-cache
Connection: keep-alive
Host: example.com
Pragma: no-cache
Upgrade-Insecure-Requests: 1
User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:59.0) Gecko/20100101 Firefox/59.0

")

(def test-post-request-form "POST /assets/github-d48e9398f9dd.css HTTP/1.1
Host: assets-cdn.github.com
User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:58.0) Gecko/20100101 Firefox/58.0
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Language: en-US,en;q=0.5
Accept-Encoding: gzip, deflate, br
Cookie: logged_in=no; _octo=GH1.1.639839137.1461439406; _ga=GA1.2.1992409474.1461439406
Connection: keep-alive
Pragma: no-cache
Cache-Control: no-cache

test1=value1&test2=value2")

(def test-post-request-json "POST /assets/github-d48e9398f9dd.css HTTP/1.1
Host: assets-cdn.github.com
User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:58.0) Gecko/20100101 Firefox/58.0
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Language: en-US,en;q=0.5
Accept-Encoding: gzip, deflate, br
Cookie: logged_in=no; _octo=GH1.1.639839137.1461439406; _ga=GA1.2.1992409474.1461439406
Connection: keep-alive
Pragma: no-cache
Cache-Control: no-cache

{\"test1\":\"value1\", \"test2\":\"value2\"}")

(def test-response "HTTP/1.1 404 Not Found
Server: GitHub.com
Content-Type: text/html
X-GitHub-Request-Id: D720:2E80F:F91128:11662AE:5AAECFB3
Accept-Ranges: bytes
Date: Sun, 18 Mar 2018 20:44:35 GMT
Via: 1.1 varnish
Connection: close
X-Served-By: cache-pao17422-PAO
X-Cache: MISS
X-Cache-Hits: 0
X-Timer: S1521405876.556290,VS0,VE91
Vary: Accept-Encoding
X-Fastly-Request-ID: e114fc55011029c50a779ecf3b2ea6f551c410a7
timing-allow-origin: https://github.com
Content-Length: 342

<!DOCTYPE html>
<html>
  <head>
    <meta http-equiv=Content-type\" content=\"text/html; charset=utf-8\">
    <meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; base-uri 'self'; connect-src 'self'; form-action 'self'; img-src data:; script-src 'self'; style-src 'unsafe-inline'\">
    <meta content=\"origin\" name=\"referrer\">")

(defn method->string
  "From a request map (see parse-raw-request) return the string of the request method"
  [{:keys [method]}]
  (string/upper-case (name method)))

(defn path->string
  "From a request map return the string of the request URL path plus its query parameters"
  [{:keys [path query-params]}]
  (if (< 0 (count query-params))
    (apply str path "?" (drop-last (apply str (map #(str (first %1) "=" (second %1) "&") query-params))))
    (apply str path)))

(defn lines
  "Get the lines from a string"
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"\n"))))

(defn spaces
  "Get the space-separated words from a string"
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #" "))))

(defn questions
  "Gets the question mark separated words"
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"\?"))))

(defn amps
  "Get the ampersand-separated words"
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"&"))))

(defn double-spaces
  "Get the words separated by double spaces"
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"\n\n"))))

(defn colons
  "Get the words separated by colons"
  [raw-string]
  (if (nil? raw-string)
    []
    (let [splits (string/split raw-string #":")]
      ;; Sometimes, there might be colons in the other headers, like use agent
      (into [] (map string/trim [(first splits) (apply str (rest splits))])))))

(defn semi-colons
  "Get the words separated by semi-colons"
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #";"))))

(defn equals
  "Get the words separated by equals signs"
  [raw-string]
  (string/split raw-string #"="))

(def memo-semis (memoize semi-colons))
(def memo-equals (memoize equals))
(def memo-colons (memoize colons))
(def memo-double (memoize double-spaces))
(def memo-amps (memoize amps))
(def memo-questions (memoize questions))
(def memo-spaces (memoize spaces))
(def memo-lines  (memoize lines))

(defn request->body
  "Parse the request body string from a raw request string"
  [raw-request]
  (let [doubles (memo-double raw-request)]
    (if (= (count doubles) 1)
      {:body-type :empty :body ""} ;; Get requests will have empty bodies
      (let [body (last doubles)] 
        (try
          (merge {:body-type :json} {:parsed-body (json/read-str body)}) ;; try to read a JSON string. If an exception happens, assume it's a form post
          (catch Exception e
            (merge {:body-type :form} {:parsed-body (into {} (map memo-equals (memo-amps body)))})))))))

(defn request->content-type
  "Parses the 'Content-Type' header from an HTTP request string"
  [raw-request]
  (string/split ""))

(defn request->query-params
  "Parses a query-params map from a raw request string"
  [raw-request]
  (let [query-seq (memo-questions (second (memo-spaces (first (memo-lines raw-request)))))]
    (if (> (count query-seq) 1)
     (into {} (map 
                #(let [s (memo-equals %)] 
                   (if (= 1 (count s))
                     (conj s "") 
                     s))
                (memo-amps (second query-seq))))
      {})))

(defn request->headers
  "Parses a header map from a raw request string"
  [raw-request]
  (into {} (map #(memo-colons %1) (take-while #(not-empty %1) (drop 1 (memo-lines raw-request))))))

(defn request->method
  "Parses a HTTP method symbol from a raw request string"
  [raw-request]
  (when-let [method (first (memo-spaces (first (memo-lines raw-request))))]
    (keyword (string/lower-case method))))

(defn request->path
  "Parses a URI path string from a raw request string"
  [raw-request]
  (first (memo-questions (second (memo-spaces (first (memo-lines raw-request)))))))

(defn request->version
  "Parses a HTTP version from a raw request string"
  [raw-request]
  (last (memo-spaces (first (memo-lines raw-request)))))

(defn request->content-length
  "Parses the content-length header from an HTTP request string"
  [raw-request]
  (let [splits (string/split raw-request #"Content-Length:")]
    (if (= (count splits) 2)
      (try (Integer/parseInt (string/trim (first (string/split (second splits) #"\n")))) (catch Exception e 0))
      0)))

(defn request->status-code
  "Parses the error code from an HTTP response string"
  [raw-request]
  (try 
    (Integer/parseInt (second (memo-spaces (first (memo-lines raw-request)))))
    (catch Exception e)))

(defn request->status-msg
  "Parses the error message from an HTTP response string"
  [raw-request]
  (string/join " " (drop 2 (memo-spaces (first (memo-lines raw-request))))))

(defn form->body
  "Transform map of values into a POST body form request format"
  [form]
  (let [body (reduce #(str %1 (str (first %2) "=" (second %2) "&")) "" form)]
    (when (> (count body) 0)
      (subs body 0 (dec (count body))))))

(defmulti encode 
  "Encoding multi that knows how to encode forms and JSON depending on the body type"
  (fn [req] (:body-type req)))

(defmethod encode :json
  [{:keys [parsed-body]}]
  (json/write-str parsed-body))

(defmethod encode :form
  [{:keys [parsed-body]}]
  (form->body parsed-body))

(defmethod encode :empty
  [{:keys [parsed-body]}]
  "")
 
(defmethod encode :default
  [{:keys [body-type]}]
  (println (str "The encoding form " body-type " is not supported.")))

(defn build-raw-request
  "From a request map (See parse-raw-request) build a raw request"
  [request-map]
  (let [req (string/join "\n" (keep identity [(string/join " " [(method->string request-map) (path->string request-map) (:version request-map)])
                                     (if (< 0 (count (:headers request-map)))
                                       (string/join "\n" (map #(string/join ": " %1) (:headers request-map))))
                                     (if (< 0 (count (:cookies request-map))) 
                                       (str "Cookie: " (string/join "; " (map #(string/join "=" %1) (:cookies request-map)))))]))]
    (if-not (= (:body-type request-map) :empty)
      (string/join "\n\n" [req (encode request-map)])
      (str req "\n\n"))))

(defn send-http
  "Send a string over a socket"
  [writer msg]
  (.write writer msg)
  (.flush writer))

(defn receive-http-headers
  "Receive a string over a socket that represents HTTP headers."
  [reader]
  (try
    (loop [http ""]
      (if (not= (apply str (take-last 2 http)) "\n\n")
        (let [rec (.readLine reader)]
          (recur (str http rec "\n")))
        http))
    (catch Exception e (println (.toString e)))))

(defn trust-anything-manager
  "TrustManager that trusts anything"
  []
  (proxy [X509TrustManager] []
    (getAcceptedIssuers [] nil)
    (checkClientTrusted [certs auth-type])
    (checkServerTrusted [certs auth-type])))

(defn trust-anything-ssl-context
  "Generate an SSL Context that trusts all certificates."
  []
  (doto (SSLContext/getInstance "TLS")
    (.init nil (into-array [(trust-anything-manager)]) nil)))

(def memo-trust-anything-ssl-context (memoize trust-anything-ssl-context))

(defn tls-socket
  "Create a TLS socket to a host and port"
  [host port]
  (try (let [ssf (cast SSLSocketFactory (.getSocketFactory (memo-trust-anything-ssl-context)))
             sock (.createSocket ssf)]
         (.connect sock (new InetSocketAddress host port) 5000) ;; connect timeout
         (.setSoTimeout sock 5000) ;; read timeout
         sock)
       ;; Can't really recover from this. Print the error.
       (catch Exception e (println (.toString e)))))

(defn socket 
  "Create a regular socket to a host and port"
  [host port]
  (try (let [sock (new Socket)]
         (.connect sock (new InetSocketAddress host port) 5000) ;; connect timeout
         (.setSoTimeout sock 5000) ;; read timeout
         sock)
       ;; Can't really recover from this. Print the error.
       (catch Exception e (println (.toString e)))))

(defn request
  "Make a socket connection and send the HTTP request."
  [host port msg & {:keys [tls] :or {tls true}}]
  (if-let [sock (if tls (tls-socket host port) (socket host port))]
    (try (with-open [writer (io/writer sock)
                     reader (io/reader sock)]           
           (send-http writer msg) 
           (if-let [headers (receive-http-headers reader)]
             (if-let [status-code (request->status-code headers)]
               {:headers (request->headers headers) :content-length (request->content-length headers) :status-code status-code :status-msg (request->status-msg headers)}
               {:error (str "Looks like you might have chosen the wrong protocol. Didn't receive a valid status code or message from the server")})
             {:error (str "Connection timeout reading from " host ":" port)}))
         (catch Exception e {:error (str "Looks like you might have chosen the wrong protocol. Socket closed unexpectedly: " (.toString e))}))
    {:error (str "Connection error to " host ":" port)}))

(defn parse-raw-request
  "Parse a string into a request map"
  [request-str]
  (let [opts (merge (request->body request-str)
                    {:headers (request->headers request-str)}
                    {:query-params (request->query-params request-str)}
                    {:method (request->method request-str)}
                    {:path (request->path request-str)}
                    {:version (request->version request-str)})]
    (update-in
     (merge opts
            {:host (get-in opts [:headers "Host"])}
            {:cookies (into {} (map #(let [me (memo-equals %)]
                                       [(first me) (apply str (rest me))]) (memo-semis  (get-in opts [:headers "Cookie"]))))}
            {:body (encode opts)})
     [:headers] dissoc "Cookie")))

(defn remove-http-element
  "Given a key, an HTTP location (:cookies, :headers, :query-params, :parsed-body), and a request mapping, create a new request mapping without that key from that location"
  [key location request-map]
  (merge {:removed [key location]} (update-in request-map [location] dissoc key)))

(defn remove-http-elements
  "Given a location, create unique request mappings with each of the location's elements removed"
  [location request-map]
  (let [params (location request-map)]
    (when (and params (< 0 (count params)))
      (map #(remove-http-element (first %1) location request-map) params))))

(defn enumerate-requests
  "Enumerate the different request mappings with each request removing either a cookie, query parameter, header, or POST body parameter"
  [base-request-map]
  (concat (remove-http-elements :query-params base-request-map)
          (remove-http-elements :parsed-body base-request-map)
          (remove-http-elements :headers base-request-map)
          (remove-http-elements :cookies base-request-map)))

(defn prequest
  "Given a set of request maps, in parallel, make the HTTP requests"
  [host port tls requests]
  (doall (pmap #(merge {:removed (:removed %)} (request host port (build-raw-request %) :tls tls)) requests)))

(defn run 
  "Bulk main program"
  [{:keys [host port http req]}]
  (let [base-req (parse-raw-request req)
        base-resp (request host port req :tls (not http))]
        (if (:error base-resp)
          (println (str "Error: " (:error base-resp)))
          (do            
            (println "Original request:")
            (println "-----------------")
            (println req)
            (println "")
            (println "Base response:")
            (println "-----------------")
            (pprint base-resp)
            (println "")
            (let [base-stripped (reduce  ;; Remove all the http elements that don't match the base
                                 #(apply remove-http-element (conj %2 %1))
                                 base-req
                                 ;; From all the response, filter the responses that match our original and only return the removed elements from each
                                 (map :removed (filter #(match base-resp %) (prequest host port (not http) (enumerate-requests (parse-raw-request req))))))]
              (println "Striped request:")
              (println "-----------------")
              (let [sreq (build-raw-request base-stripped)]
                (println sreq)
                (println "")
                (println "Stripped response:")
                (println "-----------------")
                (pprint (request host port sreq :tls (not http)))))))))

(defn -main
  "Run!"
  [& args]
  (let [{:keys [exit-message ok? opts]} (validate-opts args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)      
      (run opts)))
  1)


(comment
  (def opts {:host "google-gruyere.appspot.com" :port 443 :http false :req (slurp "/home/jacob/request.txt")}))
