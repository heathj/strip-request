(ns strip-request.core-test
  (:require [clojure.test :refer :all]
            [strip-request.core :refer :all]))

(def test2-req "GET / HTTP/1.1
Host: example.com
User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/60.0
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Language: en-US,en;q=0.5
Accept-Encoding: gzip, deflate
Connection: keep-alive
Upgrade-Insecure-Requests: 1
Pragma: no-cache
Cache-Control: no-cache

")

(def test2-ans "GET / HTTP/1.1
Accept-Encoding: gzip, deflate
Host: example.com

")

(deftest request2-https
  (testing "Integration test for request 2 (example.com) over HTTPS to make sure it produces the proper stripped output."
    (let [host "example.com" 
          port 443 
          http false 
          req test2-req
          base-resp (request host port req)
          base-stripped (strip-request (parse-raw-request req) base-resp host port http)
          sreq (build-raw-request base-stripped)]
      (is (= test2-ans sreq)))))

(deftest request2-http
  (testing "Integration test for request 2 (exaple.com) to make sure it produces the proper stripped output."
    (let [host "example.com" 
          port  80
          http true 
          req test2-req
          base-resp (request host port req :tls (not http))
          base-stripped (strip-request (parse-raw-request req) base-resp host port http)
          sreq (build-raw-request base-stripped)]
      (is (= test2-ans sreq)))))
