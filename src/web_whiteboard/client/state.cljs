(ns web-whiteboard.client.state
  "The state model needed for the client"
  (:require [cognitect.transit :as transit]
            [web-whiteboard.client.handlers.websocket :as hws]
            [cljs.core.async :refer [chan mult tap]]
            [clojure.string]))

(defn get-query-params
  "Turn the search segment of a url into a hash-map of query params"
  [search]
  (if (empty? search)
    {}
    (let [chunks (-> search
                     (subs 1)
                     (clojure.string/split #"&"))
          list-of-pairs (map #(clojure.string/split % #"=") chunks)]
      (reduce (fn [acc [name val]]
                (assoc acc (keyword name) val))
              {}
              list-of-pairs))))

(def query-params
  (get-query-params (-> js/window
                     .-location
                     .-search)))
(.log js/console query-params)

(def hostname
  "The client-determined hostname"
  (-> js/window
      (.-location)
      (.-hostname)))

(def port
  "The client-determined port"
  (-> js/window
      (.-location)
      (.-port)))

(def host
  "The client-determined host, using hostname and port"
  (str hostname ":" port))

(def ws-url
  "The websocket endpoint to use"
  (str "ws://" host "/ws/whiteboard/"))

(def brepl-url
  "The endpoint to use for the browser connected repl"
  (str "http://" hostname ":9000/repl"))

;; TODO: Integrate the queue for if send is called while a websocket connection is still
;; being established so that nothing is dropped
;; TODO: Work the log-index into interactions with the server so that server can push
;; notifications for what happened while disconnected

(defn- init-app-state
  "The app-state, without [:server :ws] available"
  [ws-url]
  (atom {:server {:ws nil
                  :queue []
                  :url ws-url}
         
         :client {:id (get query-params :cid (str "client:" (random-uuid)))
                  :ui {:is-mouse-down? false
                       :canvas {:id "canvas"}
                       :pen {:color "#0000FF"
                             :radius 3
                             :example-id "pen-example"}}}
         
         :whiteboard {:id (get query-params :wid (str "whiteboard:" (random-uuid)))}
         
         :log-index nil
         :mode :realtime
         :transit {:writer (transit/writer :json)
                   :reader (transit/reader :json)}
         :channels {:ws {:from (chan)}
                    :ui {:to (chan)}}
         :connected false}))

(defn- configure-chans
  "Configure the application channels"
  [app-state]
  (let [s @app-state
        ws-chan (get-in s [:channels :ws :from])
        ui-chan (get-in s [:channels :ui :to])
        ws-mult (mult ws-chan)]
    (tap ws-mult ui-chan)))

(defn create-app-state
  "Create the app-state
  
  NOTE: There is some trickiness due to the fact that to create a websocket
  you need to know something about the app-state"
  [ws-url]
  (let [app-state (init-app-state ws-url)
        websocket (hws/create-ws app-state (fn [_] nil))]
    (swap! app-state (fn [prev]
                       (assoc-in prev [:server :ws] websocket)))
    (configure-chans app-state)
    app-state))
