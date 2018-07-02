(ns strip-request.core
  (:require [clojure.string :as string]
            [clojure.data.json :as json]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint])

  (:import (java.net Socket InetAddress InetSocketAddress)
           (javax.net.ssl SSLSocketFactory X509TrustManager SSLContext)
           (java.security KeyStore)
           (java.io OutputStreamWriter InputStreamReader PushbackReader))
  (:gen-class))

(def cli-options
  "The list of command line arguments to this program"
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
  "Given two request maps, decide if they match. In order for a match, they have
  to share the same content-length, status code, and status message."
  [base new]
  (and (match-on base new :content-length)
       (match-on base new :status-code)
       (match-on base new :status-msg)))

(defn usage 
  [options-summary]
  "Prints the usage menu."
  (->> ["strip-request: a command line tool for stripping away unnecessary headers, query parameters, and cookies from requests."
        ""
        "Usage: strip-request [options]"
        ""
        "Options:"
        options-summary
        ""]
       (string/join \newline)))

(defn error-msg
  "Print the errors in a unified way."
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-opts
  "Validate all the command line arguments."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
     (:help options)
     {:exit-message (usage summary) :ok? true}
     errors
     {:exit-message (error-msg errors)}
     (empty? (:req options))
     {:exit-message (str (error-msg ["Need to specify a request to send.\n\n"]) (usage summary))}
     :else
     {:opts options})))

(defn exit
  "Print a message and exit with a status."
  [status msg]
  (println msg)
  (System/exit status))



(defn method->string
  "From a request map, return the string of the request method."
  [{:keys [method]}]
  (string/upper-case (name method)))

(defn path->string
  "From a request map, return the string of the request URL path and its query parameters."
  [{:keys [path query-params]}]
  (if (< 0 (count query-params))
    (apply str path "?" (drop-last (apply str (map #(str (first %1) "=" (second %1) "&") query-params))))
    (apply str path)))

(defn split-lines
  "Get the lines from a string."
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"\n"))))

(defn split-spaces
  "Get the space-separated words from a string."
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #" "))))

(defn split-questions
  "Gets the question mark separated words."
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"\?"))))

(defn split-amps
  "Get the ampersand-separated words."
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"&"))))

(defn split-double-spaces
  "Get the words separated by double spaces."
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #"\n\n"))))

(defn split-colons
  "Get the words separated by colons."
  [raw-string]
  (if (nil? raw-string)
    []
    (let [splits (string/split raw-string #":")]
      ;; Sometimes, there might be colons in the other headers, like User-Agent
      (into [] (map string/trim [(first splits) (apply str (rest splits))])))))

(defn split-semi-colons
  "Get the words separated by semi-colons."
  [raw-string]
  (if (nil? raw-string)
    []
    (map string/trim (string/split raw-string #";"))))

(defn split-equals
  "Get the words separated by equals signs."
  [raw-string]
  (string/split raw-string #"="))

(def memo-split-semi-colons (memoize split-semi-colons))
(def memo-split-equals (memoize split-equals))
(def memo-split-colons (memoize split-colons))
(def memo-split-double-spaces (memoize split-double-spaces))
(def memo-split-amps (memoize split-amps))
(def memo-split-questions (memoize split-questions))
(def memo-split-spaces (memoize split-spaces))
(def memo-split-lines (memoize split-lines))

(defn request->body
  "Parse the request body string from a raw request string."
  [raw-request]
  (let [doubles (memo-split-double-spaces raw-request)]
    (if (or (= (count doubles) 1) (and (= (count doubles) 2) (empty? (second doubles))))
      {:body-type :empty :body ""} ;; Get requests will have empty bodies
      (let [body (last doubles)] 
        (try
          ;; try to read a JSON string. If an exception happens, assume it's a
          ;; form post
          (merge {:body-type :json} {:parsed-body (json/read-str body)})
          (catch Exception e
            (merge {:body-type :form} {:parsed-body (into {} (map memo-split-equals (memo-split-amps body)))})))))))

(defn request->query-params
  "Parses a query-params map from a raw request string."
  [raw-request]
  (let [query-seq (memo-split-questions (second (memo-split-spaces (first (memo-split-lines raw-request)))))]
    (if (> (count query-seq) 1)
     (into {} (map 
                #(let [s (memo-split-equals %)] 
                   (if (= 1 (count s))
                     (conj s "") 
                     s))
                (memo-split-amps (second query-seq))))
      {})))

(defn request->headers
  "Parses a header map from a raw request string."
  [raw-request]
  (into {} (map #(memo-split-colons %1) (take-while #(not-empty %1) (drop 1 (memo-split-lines raw-request))))))

(defn request->method
  "Parses a HTTP method symbol from a raw request string."
  [raw-request]
  (when-let [method (first (memo-split-spaces (first (memo-split-lines raw-request))))]
    (keyword (string/lower-case method))))

(defn request->path
  "Parses a URI path string from a raw request string."
  [raw-request]
  (first (memo-split-questions (second (memo-split-spaces (first (memo-split-lines raw-request)))))))

(defn request->version
  "Parses a HTTP version from a raw request string."
  [raw-request]
  (last (memo-split-spaces (first (memo-split-lines raw-request)))))

(defn request->content-length
  "Parses the content-length header from an HTTP request string."
  [raw-request]
  (let [splits (string/split raw-request #"Content-Length:")]
    (if (= (count splits) 2)
      (try (Integer/parseInt (string/trim (first (string/split (second splits) #"\n")))) (catch Exception e 0))
      0)))

(defn request->status-code
  "Parses the error code from an HTTP response string."
  [raw-request]
  (try 
    (Integer/parseInt (second (memo-split-spaces (first (memo-split-lines raw-request)))))
    (catch Exception e)))

(defn request->status-msg
  "Parses the error message from an HTTP response string."
  [raw-request]
  (string/join " " (drop 2 (memo-split-spaces (first (memo-split-lines raw-request))))))

(defn form->body
  "Transform map of values into a POST body form request format."
  [form]
  (let [body (reduce #(str %1 (str (first %2) "=" (second %2) "&")) "" form)]
    (when (> (count body) 0)
      (subs body 0 (dec (count body))))))

(defmulti encode 
  "Encoding multimethod that knows how to encode forms and JSON depending on the body type."
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
  "From a request map (See parse-raw-request) build a raw request."
  [request-map]
  (let [req (string/join \newline 
                         (keep identity [(string/join " " [(method->string request-map) 
                                                           (path->string request-map)
                                                           (:version request-map)])
                                         (if (< 0 (count (:headers request-map)))
                                           (string/join \newline 
                                                        (map #(string/join ": " %1) (:headers request-map))))
                                         (if (< 0 (count (:cookies request-map))) 
                                           (str "Cookie: " (string/join "; " 
                                                                        (map #(string/join "=" %1) (:cookies request-map)))))]))]
    (if-not (= (:body-type request-map) :empty)
      (string/join \newline \newline [req (encode request-map)])
      (str req \newline \newline))))

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
  "TrustManager that trusts anything...relax it's ok."
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

(def memo-trust-anything-ssl-context 
  "We can reuse this context between requests, so save it."
  (memoize trust-anything-ssl-context))

(defn tls-socket
  "Create a TLS socket to a host and port."
  [host port]
  (try (let [ssf (cast SSLSocketFactory (.getSocketFactory (memo-trust-anything-ssl-context)))
             sock (.createSocket ssf)]
         (.connect sock (new InetSocketAddress host port) 5000) ;; connect timeout
         (.setSoTimeout sock 5000) ;; read timeout
         sock)
       ;; Can't really recover from this. Print the error.
       (catch Exception e (println (.toString e)))))

(defn socket 
  "Create a regular socket to a host and port."
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
  "Parse a string into a request map."
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
            {:cookies (into {} (map #(let [me (memo-split-equals %)]
                                       [(first me) (apply str (rest me))]) (memo-split-semi-colons (get-in opts [:headers "Cookie"]))))}
            {:body (encode opts)})
     [:headers] dissoc "Cookie")))

(defn remove-http-element
  "Given a key, an HTTP location (:cookies, :headers, :query-params, :parsed-body),
  and a request mapping, create a new request mapping without that key from that 
  location."
  [key location request-map]
  (merge {:removed [key location]} (update-in request-map [location] dissoc key)))

(defn remove-http-elements
  "Given a location, create unique request mappings with each of the location's 
  elements removed."
  [location request-map]
  (let [params (location request-map)]
    (when (and params (< 0 (count params)))
      (map #(remove-http-element (first %1) location request-map) params))))

(defn enumerate-requests
  "Enumerate the different request mappings with each request removing either a 
  cookie, query parameter, header, or POST body parameter."
  [base-request-map]
  (concat (remove-http-elements :query-params base-request-map)
          (remove-http-elements :parsed-body base-request-map)
          (remove-http-elements :headers base-request-map)
          (remove-http-elements :cookies base-request-map)))

(defn prequest
  "Given a set of request maps, in parallel, make the HTTP requests."
  [host port tls requests]
  (doall 
   (pmap #(merge {:removed (:removed %)} (request host port (build-raw-request %) :tls tls)) requests)))

(defn strip-request
  "Given a raw request and the expected response map, strip the request and
  return a new minimized request-map"
  [base-req base-resp host port http]
  (reduce #(apply remove-http-element (conj %2 %1)) base-req
          (map :removed
               (filter #(match base-resp %)
                       (prequest host port (not http) (enumerate-requests base-req))))))

(defn run 
  "Bulk main program."
  [{:keys [host port http req]}]
  (let [base-resp (request host port req :tls (not http))]
        (if (:error base-resp)
          (println (str "Error: " (:error base-resp)))
          (do            
            (println "Original request:")
            (println "-----------------")
            (print req)
            (println "-----------------")
            (println "Base response:")
            (println "-----------------")
            (pprint/pprint base-resp)
            (println "")
            (let [base-stripped (strip-request (parse-raw-request req) base-resp host port http)
                  sreq (build-raw-request base-stripped)]
              (println "Striped request:")
              (println "-----------------")
              (print sreq)
              (println "-----------------")
              (println "Stripped response:")
              (println "-----------------")
              (pprint/pprint (request host port sreq :tls (not http))))))))

(defn -main
  "Run program from the CLI."
  [& args]
  (let [{:keys [exit-message ok? opts]} (validate-opts args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)      
      (doall
       (run opts)
       (exit 0 ""))))
  1)
