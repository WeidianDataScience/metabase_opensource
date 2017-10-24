(ns metabase.middleware
  "Metabase-specific middleware functions & configuration."
  (:require [cheshire
             [core :as json]
             [generate :refer [add-encoder encode-nil encode-str]]]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [metabase
             [config :as config]
             [db :as mdb]
             [public-settings :as public-settings]
             [util :as u]]
            [metabase.api.common :refer [*current-user* *current-user-id* *current-user-permissions-set* *is-superuser?*]]
            [metabase.api.common.internal :refer [*automatically-catch-api-exceptions*]]
            [metabase.core.initialization-status :as init-status]
            [metabase.models
             [session :refer [Session]]
             [setting :refer [defsetting]]
             [user :as user :refer [User]]]
            monger.json
            [ring.core.protocols :as protocols]
            [ring.util.response :as response]
            [toucan
             [db :as db]
             [models :as models]])
  (:import com.fasterxml.jackson.core.JsonGenerator
           java.io.OutputStream))

;;; # ------------------------------------------------------------ UTIL FNS ------------------------------------------------------------

(defn- api-call?
  "Is this ring request an API call (does path start with `/api`)?"
  [{:keys [^String uri]}]
  (and (>= (count uri) 4)
       (= (.substring uri 0 4) "/api")))

(defn- index?
  "Is this ring request one that will serve `index.html` or `init.html`?"
  [{:keys [uri]}]
  (or (zero? (count uri))
      (not (or (re-matches #"^/app/.*$" uri)
               (re-matches #"^/api/.*$" uri)
               (re-matches #"^/public/.*$" uri)
               (re-matches #"^/favicon.ico$" uri)))))

(defn- public?
  "Is this ring request one that will serve `public.html`?"
  [{:keys [uri]}]
  (re-matches #"^/public/.*$" uri))

(defn- embed?
  "Is this ring request one that will serve `public.html`?"
  [{:keys [uri]}]
  (re-matches #"^/embed/.*$" uri))

;;; # ------------------------------------------------------------ AUTH & SESSION MANAGEMENT ------------------------------------------------------------

(def ^:private ^:const ^String metabase-session-cookie "metabase.SESSION_ID")
(def ^:private ^:const ^String metabase-session-header "x-metabase-session")
(def ^:private ^:const ^String metabase-api-key-header "x-metabase-apikey")

(def ^:const response-unauthentic "Generic `401 (Unauthenticated)` Ring response map." {:status 401, :body "Unauthenticated"})
(def ^:const response-forbidden   "Generic `403 (Forbidden)` Ring response map."       {:status 403, :body "Forbidden"})


(defn wrap-session-id
  "Middleware that sets the `:metabase-session-id` keyword on the request if a session id can be found.

   We first check the request :cookies for `metabase.SESSION_ID`, then if no cookie is found we look in the
   http headers for `X-METABASE-SESSION`.  If neither is found then then no keyword is bound to the request."
  [handler]
  (comp handler (fn [{:keys [cookies headers] :as request}]
                  (if-let [session-id (or (get-in cookies [metabase-session-cookie :value])
                                          (headers metabase-session-header))]
                    (assoc request :metabase-session-id session-id)
                    request))))

(defn- session-with-id
  "Fetch a session with SESSION-ID, and include the User ID and superuser status associated with it."
  [session-id]
  (db/select-one [Session :created_at :user_id (db/qualify User :is_superuser)]
    (mdb/join [Session :user_id] [User :id])
    (db/qualify User :is_active) true
    (db/qualify Session :id) session-id))

(defn- session-age-ms [session]
  (- (System/currentTimeMillis) (or (when-let [^java.util.Date created-at (:created_at session)]
                                      (.getTime created-at))
                                    0)))

(defn- session-age-minutes [session]
  (quot (session-age-ms session) 60000))

(defn- session-expired? [session]
  (> (session-age-minutes session)
     (config/config-int :max-session-age)))

(defn- current-user-info-for-session
  "Return User ID and superuser status for Session with SESSION-ID if it is valid and not expired."
  [session-id]
  (when (and session-id (init-status/complete?))
    (when-let [session (session-with-id session-id)]
      (when-not (session-expired? session)
        {:metabase-user-id (:user_id session)
         :is-superuser?    (:is_superuser session)}))))

(defn- add-current-user-info [{:keys [metabase-session-id], :as request}]
  (merge request (current-user-info-for-session metabase-session-id)))

(defn wrap-current-user-id
  "Add `:metabase-user-id` to the request if a valid session token was passed."
  [handler]
  (comp handler add-current-user-info))


(defn enforce-authentication
  "Middleware that returns a 401 response if REQUEST has no associated `:metabase-user-id`."
  [handler]
  (fn [{:keys [metabase-user-id] :as request}]
    (if metabase-user-id
      (handler request)
      response-unauthentic)))

(def ^:private current-user-fields
  (vec (concat [User :is_active :google_auth :ldap_auth] (models/default-fields User))))

(defn bind-current-user
  "Middleware that binds `metabase.api.common/*current-user*`, `*current-user-id*`, `*is-superuser?*`, and `*current-user-permissions-set*`.

   *  `*current-user-id*`             int ID or nil of user associated with request
   *  `*current-user*`                delay that returns current user (or nil) from DB
   *  `*is-superuser?*`               Boolean stating whether current user is a superuser.
   *  `current-user-permissions-set*` delay that returns the set of permissions granted to the current user from DB"
  [handler]
  (fn [request]
    (if-let [current-user-id (:metabase-user-id request)]
      (binding [*current-user-id*              current-user-id
                *is-superuser?*                (:is-superuser? request)
                *current-user*                 (delay (db/select-one current-user-fields, :id current-user-id))
                *current-user-permissions-set* (delay (user/permissions-set current-user-id))]
        (handler request))
      (handler request))))


(defn wrap-api-key
  "Middleware that sets the `:metabase-api-key` keyword on the request if a valid API Key can be found.
   We check the request headers for `X-METABASE-APIKEY` and if it's not found then then no keyword is bound to the request."
  [handler]
  (comp handler (fn [{:keys [headers] :as request}]
                  (if-let [api-key (headers metabase-api-key-header)]
                    (assoc request :metabase-api-key api-key)
                    request))))


(defn enforce-api-key
  "Middleware that enforces validation of the client via API Key, cancelling the request processing if the check fails.

   Validation is handled by first checking for the presence of the `:metabase-api-key` on the request.  If the api key
   is available then we validate it by checking it against the configured `:mb-api-key` value set in our global config.

   If the request `:metabase-api-key` matches the configured `:mb-api-key` value then the request continues, otherwise we
   reject the request and return a 403 Forbidden response."
  [handler]
  (fn [{:keys [metabase-api-key] :as request}]
    (if (= (config/config-str :mb-api-key) metabase-api-key)
      (handler request)
      ;; default response is 403
      response-forbidden)))


;;; # ------------------------------------------------------------ SECURITY HEADERS ------------------------------------------------------------

(defn- cache-prevention-headers
  "Headers that tell browsers not to cache a response."
  []
  {"Cache-Control" "max-age=0, no-cache, must-revalidate, proxy-revalidate"
   "Expires"        "Tue, 03 Jul 2001 06:00:00 GMT"
   "Last-Modified"  (u/format-date :rfc822)})

(def ^:private ^:const strict-transport-security-header
  "Tell browsers to only access this resource over HTTPS for the next year (prevent MTM attacks).
   (This only applies if the original request was HTTPS; if sent in response to an HTTP request, this is simply ignored)"
  {"Strict-Transport-Security" "max-age=31536000"})

(def ^:private ^:const content-security-policy-header
  "`Content-Security-Policy` header. See [http://content-security-policy.com](http://content-security-policy.com) for more details."
  {"Content-Security-Policy" (apply str (for [[k vs] {:default-src ["'none'"]
                                                      :script-src  ["'unsafe-inline'"
                                                                    "'unsafe-eval'"
                                                                    "'self'"
                                                                    "https://maps.google.com"
                                                                    "https://apis.google.com"
                                                                    "https://www.google-analytics.com" ; Safari requires the protocol
                                                                    "https://*.googleapis.com"
                                                                    "https://analysis.koudai.com"
                                                                    "http://analysis.koudai.com"
                                                                    "*.gstatic.com"
                                                                    (when config/is-dev?
                                                                      "localhost:8080")
                                                                      ]
                                                      :child-src   ["'self'"
                                                                    "https://accounts.google.com"] ; TODO - double check that we actually need this for Google Auth
                                                      :style-src   ["'unsafe-inline'"
                                                                    "'self'"]
                                                                    #_"fonts.googleapis.com"
                                                      :font-src    ["'self'"
                                                                    "fonts.gstatic.com"
                                                                    "themes.googleusercontent.com"
                                                                    (when config/is-dev?
                                                                      "localhost:8080")]
                                                      :img-src     ["*"
                                                                    "'self' data:"]
                                                      :connect-src ["'self'"
                                                                    "metabase.us10.list-manage.com"
                                                                    (when config/is-dev?
                                                                      "localhost:8080 ws://localhost:8080")]}]
                                          (format "%s %s; " (name k) (apply str (interpose " " vs)))))})

(defsetting ssl-certificate-public-key
  "Base-64 encoded public key for this site's SSL certificate. Specify this to enable HTTP Public Key Pinning.
   See http://mzl.la/1EnfqBf for more information.") ; TODO - it would be nice if we could make this a proper link in the UI; consider enabling markdown parsing

#_(defn- public-key-pins-header []
  (when-let [k (ssl-certificate-public-key)]
    {"Public-Key-Pins" (format "pin-sha256=\"base64==%s\"; max-age=31536000" k)}))

(defn- api-security-headers [] ; don't need to include all the nonsense we include with index.html
  (merge (cache-prevention-headers)
         strict-transport-security-header
         #_(public-key-pins-header)))

(defn- html-page-security-headers [& {:keys [allow-iframes?] }]
  (merge (cache-prevention-headers)
         strict-transport-security-header
         content-security-policy-header
         #_(public-key-pins-header)
         (when-not allow-iframes?
           {"X-Frame-Options"                 "DENY"})        ; Tell browsers not to render our site as an iframe (prevent clickjacking)
         {"X-XSS-Protection"                  "1; mode=block" ; Tell browser to block suspected XSS attacks
          "X-Permitted-Cross-Domain-Policies" "none"          ; Prevent Flash / PDF files from including content from site.
          "X-Content-Type-Options"            "nosniff"}))    ; Tell browser not to use MIME sniffing to guess types of files -- protect against MIME type confusion attacks

(defn add-security-headers
  "Add HTTP headers to tell browsers not to cache API responses."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (update response :headers merge (cond
                                        (api-call? request) (api-security-headers)
                                        (public? request)   (html-page-security-headers, :allow-iframes? true)
                                        (embed? request)    (html-page-security-headers, :allow-iframes? true)
                                        (index? request)    (html-page-security-headers))))))


;;; # ------------------------------------------------------------ SETTING SITE-URL ------------------------------------------------------------

;; It's important for us to know what the site URL is for things like returning links, etc.
;; this is stored in the `site-url` Setting; we can set it automatically by looking at the `Origin` or `Host` headers sent with a request.
;; Effectively the very first API request that gets sent to us (usually some sort of setup request) ends up setting the (initial) value of `site-url`

(defn maybe-set-site-url
  "Middleware to set the `site-url` Setting if it's unset the first time a request is made."
  [handler]
  (fn [{{:strs [origin host] :as headers} :headers, :as request}]
    (when (mdb/db-is-setup?)
      (when-not (public-settings/site-url)
        (when-let [site-url (or origin host)]
          (log/info "Setting Metabase site URL to" site-url)
          (public-settings/site-url site-url))))
    (handler request)))


;;; # ------------------------------------------------------------ JSON SERIALIZATION CONFIG ------------------------------------------------------------

;; Tell the JSON middleware to use a date format that includes milliseconds (why?)
(def ^:private ^:const default-date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
(intern 'cheshire.factory 'default-date-format default-date-format)
(intern 'cheshire.generate '*date-format* default-date-format)

;; ## Custom JSON encoders

;; Always fall back to `.toString` instead of barfing.
;; In some cases we should be able to improve upon this behavior; `.toString` may just return the Class and address, e.g. `some.Class@72a8b25e`
;; The following are known few classes where `.toString` is the optimal behavior:
;; *  `org.postgresql.jdbc4.Jdbc4Array` (Postgres arrays)
;; *  `org.bson.types.ObjectId`         (Mongo BSON IDs)
;; *  `java.sql.Date`                   (SQL Dates -- .toString returns YYYY-MM-DD)
(add-encoder Object encode-str)

(defn- encode-jdbc-clob [clob, ^JsonGenerator json-generator]
  (.writeString json-generator (u/jdbc-clob->str clob)))

;; stringify JDBC clobs
(add-encoder org.h2.jdbc.JdbcClob               encode-jdbc-clob) ; H2
(add-encoder org.postgresql.util.PGobject       encode-jdbc-clob) ; Postgres

;; Encode BSON undefined like `nil`
(add-encoder org.bson.BsonUndefined encode-nil)

;; Binary arrays ("[B") -- hex-encode their first four bytes, e.g. "0xC42360D7"
(add-encoder (Class/forName "[B") (fn [byte-ar, ^JsonGenerator json-generator]
                                    (.writeString json-generator ^String (apply str "0x" (for [b (take 4 byte-ar)]
                                                                                           (format "%02X" b))))))

;;; # ------------------------------------------------------------ LOGGING ------------------------------------------------------------

(defn- log-response [{:keys [uri request-method]} {:keys [status body]} elapsed-time db-call-count]
  (let [log-error #(log/error %) ; these are macros so we can't pass by value :sad:
        log-debug #(log/debug %)
        log-warn  #(log/warn  %)
        [error? color log-fn] (cond
                                (>= status 500) [true  'red   log-error]
                                (=  status 403) [true  'red   log-warn]
                                (>= status 400) [true  'red   log-debug]
                                :else           [false 'green log-debug])]
    (log-fn (str (u/format-color color "%s %s %d (%s) (%d DB calls)" (.toUpperCase (name request-method)) uri status elapsed-time db-call-count)
                 ;; only print body on error so we don't pollute our environment by over-logging
                 (when (and error?
                            (or (string? body) (coll? body)))
                   (str "\n" (u/pprint-to-str body)))))))

(defn log-api-call
  "Middleware to log `:request` and/or `:response` by passing corresponding OPTIONS."
  [handler & options]
  (fn [{:keys [uri], :as request}]
    (if (or (not (api-call? request))
            (= uri "/api/health")     ; don't log calls to /health or /util/logs because they clutter up
            (= uri "/api/util/logs")) ; the logs (especially the window in admin) with useless lines
      (handler request)
      (let [start-time (System/nanoTime)]
        (db/with-call-counting [call-count]
          (u/prog1 (handler request)
            (log-response request <> (u/format-nanoseconds (- (System/nanoTime) start-time)) (call-count))))))))


;;; ------------------------------------------------------------ EXCEPTION HANDLING ------------------------------------------------------------

(defn genericize-exceptions
  "Catch any exceptions thrown in the request handler body and rethrow a generic 400 exception instead.
   This minimizes information available to bad actors when exceptions occur on public endpoints."
  [handler]
  (fn [request]
    (try (binding [*automatically-catch-api-exceptions* false]
           (handler request))
         (catch Throwable e
           (log/warn (.getMessage e))
           {:status 400, :body "An error occurred."}))))

(defn message-only-exceptions
  "Catch any exceptions thrown in the request handler body and rethrow a 400 exception that only has
   the message from the original instead (i.e., don't rethrow the original stacktrace).
   This reduces the information available to bad actors but still provides some information that will
   prove useful in debugging errors."
  [handler]
  (fn [request]
    (try (binding [*automatically-catch-api-exceptions* false]
           (handler request))
         (catch Throwable e
           {:status 400, :body (.getMessage e)}))))

;;; ------------------------------------------------------------ EXCEPTION HANDLING ------------------------------------------------------------

(def ^:private ^:const streaming-response-keep-alive-interval-ms
  "Interval between sending newline characters to keep Heroku from terminating
   requests like queries that take a long time to complete."
  (* 1 1000))

;; Handle ring response maps that contain a core.async chan in the :body key:
;;
;; {:status 200
;;  :body (async/chan)}
;;
;; and send each string sent to that queue back to the browser as it arrives
;; this avoids output buffering in the default stream handling which was not sending
;; any responses until ~5k characters where in the queue.
(extend-protocol protocols/StreamableResponseBody
  clojure.core.async.impl.channels.ManyToManyChannel
  (write-body-to-stream [output-queue _ ^OutputStream output-stream]
    (log/debug (u/format-color 'green "starting streaming request"))
    (with-open [out (io/writer output-stream)]
      (loop [chunk (async/<!! output-queue)]
        (when-not (= chunk ::EOF)
          (.write out (str chunk))
          (try
            (.flush out)
            (catch org.eclipse.jetty.io.EofException e
              (log/info (u/format-color 'yellow "connection closed, canceling request %s" (type e)))
              (async/close! output-queue)
              (throw e)))
          (recur (async/<!! output-queue)))))))

(defn streaming-json-response
  "This midelware assumes handlers fail early or return success
   Run the handler in a future and send newlines to keep the connection open
   and help detect when the browser is no longer listening for the response.
   Waits for one second to see if the handler responds immediately, If it does
   then there is no need to stream the response and it is sent back directly.
   In cases where it takes longer than a second, assume the eventual result will
   be a success and start sending newlines to keep the connection open."
  [handler]
  (fn [request]
    (let [response            (future (handler request))
          optimistic-response (deref response streaming-response-keep-alive-interval-ms ::no-immediate-response)]
      (if (= optimistic-response ::no-immediate-response)
        ;; if we didn't get a normal response in the first poling interval assume it's going to be slow
        ;; and start sending keepalive packets.
        (let [output (async/chan 1)]
          ;; the output channel will be closed by the adapter when the incoming connection is closed.
          (future
            (loop []
              (Thread/sleep streaming-response-keep-alive-interval-ms)
              (when-not (realized? response)
                (log/debug (u/format-color 'blue "Response not ready, writing one byte & sleeping..."))
                ;; a newline padding character is used because it forces output flushing in jetty.
                ;; if sending this character fails because the connection is closed, the chan will then close.
                ;; Newlines are no-ops when reading JSON which this depends upon.
                (when-not (async/>!! output "\n")
                  (log/info (u/format-color 'yellow "canceled request %s" (future-cancel response)))
                  (future-cancel response)) ;; try our best to kill the thread running the query.
                (recur))))
          (future
            (try
              ;; This is the part where we make this assume it's a JSON response we are sending.
              (async/>!! output (json/encode (:body @response)))
              (finally
                (async/>!! output ::EOF)
                (async/close! response))))
          ;; here we assume a successful response will be written to the output channel.
          (assoc (response/response output)
            :content-type "applicaton/json"))
          optimistic-response))))
